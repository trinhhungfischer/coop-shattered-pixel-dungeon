/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.shatteredpixel.shatteredpixeldungeon.coop.net;

import com.watabou.utils.Bundle;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * The hosting side: accepts up to CoopNet.MAX_PLAYERS-1 clients, runs the lobby and forwards
 * game messages. The host player is always slot 0.
 *
 * Callbacks come from network threads; the UI/game side must hand them over to its own thread.
 */
public class CoopHost implements NetConnection.Listener {

	public interface Events {
		default void onLobbyChanged( List<CoopNet.Player> players ){}
		default void onPlayerLeft( int slot, String name, String reason ){}
		//any message that isn't part of the lobby protocol (game commands from M3 on)
		default void onGameMessage( int slot, Bundle message ){}
	}

	private final int gameVersion;
	private volatile Events events;

	private ServerSocket server;
	private volatile boolean running = false;
	private volatile boolean started = false;

	private final NetConnection[] clients = new NetConnection[CoopNet.MAX_PLAYERS];
	private final ArrayList<CoopNet.Player> players = new ArrayList<>();
	private final LanDiscovery.Beacon beacon;

	public CoopHost( int gameVersion, String hostName, String hostClass, Events events ){
		this.gameVersion = gameVersion;
		this.events = events;
		CoopNet.Player me = new CoopNet.Player();
		me.slot = 0;
		me.name = hostName;
		me.heroClass = hostClass;
		players.add( me );
		beacon = new LanDiscovery.Beacon( this );
	}

	//bind: null for all interfaces (LAN play), or e.g. 127.0.0.1 for local tests
	public synchronized void start( InetAddress bind, int port, boolean announceOnLan ) throws IOException {
		server = new ServerSocket();
		server.setReuseAddress( true );
		server.bind( new InetSocketAddress( bind, port ) );
		running = true;

		Thread accept = new Thread( this::acceptLoop, "coop-host-accept" );
		accept.setDaemon( true );
		accept.start();

		Thread heartbeat = new Thread( this::heartbeatLoop, "coop-host-heartbeat" );
		heartbeat.setDaemon( true );
		heartbeat.start();

		if (announceOnLan) beacon.start();
	}

	//the game takes over the callbacks once the run starts
	public void setEvents( Events events ){
		this.events = events;
	}

	public int port(){
		return server == null ? -1 : server.getLocalPort();
	}

	public String hostName(){
		synchronized (this){
			return players.get(0).name;
		}
	}

	public boolean isStarted(){
		return started;
	}

	public synchronized int playerCount(){
		return players.size();
	}

	public synchronized List<CoopNet.Player> players(){
		ArrayList<CoopNet.Player> copy = new ArrayList<>();
		for (CoopNet.Player p : players) copy.add( p.copy() );
		return copy;
	}

	private void acceptLoop(){
		while (running){
			try {
				Socket s = server.accept();
				NetConnection c = new NetConnection( s, this );
				c.start( "coop-host-client" );
			} catch (IOException e){
				if (running) continue;
				return;
			}
		}
	}

	private void heartbeatLoop(){
		while (running){
			try {
				Thread.sleep( CoopNet.HEARTBEAT_MS );
			} catch (InterruptedException e){
				return;
			}
			long now = System.currentTimeMillis();
			for (NetConnection c : connections()){
				if (now - c.lastReceived() > CoopNet.TIMEOUT_MS){
					c.close( "timed out" );
					continue;
				}
				Bundle ping = NetConnection.message( CoopNet.PING );
				ping.put( "at", now );
				c.send( ping );
			}
		}
	}

	private synchronized List<NetConnection> connections(){
		ArrayList<NetConnection> list = new ArrayList<>();
		for (NetConnection c : clients) if (c != null) list.add( c );
		return list;
	}

	@Override
	public void onMessage( NetConnection c, Bundle m ){
		String type = NetConnection.type( m );
		switch (type){
			case CoopNet.HELLO:
				onHello( c, m );
				return;
			case CoopNet.PING:
				Bundle pong = NetConnection.message( CoopNet.PONG );
				pong.put( "at", m.getLong("at") );
				c.send( pong );
				return;
			case CoopNet.PONG:
				int rtt = (int)(System.currentTimeMillis() - m.getLong("at"));
				synchronized (this){
					CoopNet.Player p = player( c.slot );
					if (p != null) p.ping = rtt;
				}
				return;
			case CoopNet.BYE:
				c.close( "left" );
				return;
		}
		if (c.slot < 0) return; //not through the handshake yet
		switch (type){
			case CoopNet.PICK:
				synchronized (this){
					CoopNet.Player p = player( c.slot );
					if (p != null && !started){
						p.heroClass = m.getString( "cls" );
						p.ready = false;
					}
				}
				lobbyChanged();
				return;
			case CoopNet.READY:
				synchronized (this){
					CoopNet.Player p = player( c.slot );
					if (p != null && !started) p.ready = m.getBoolean( "ready" );
				}
				lobbyChanged();
				return;
			default:
				events.onGameMessage( c.slot, m );
		}
	}

	private void onHello( NetConnection c, Bundle m ){
		String reason = null;
		int slot = -1;
		synchronized (this){
			if (m.getInt( "protocol" ) != CoopNet.PROTOCOL || m.getInt( "game" ) != gameVersion){
				reason = "version";
			} else if (started){
				reason = "started";
			} else {
				for (int i = 1; i < clients.length; i++){
					if (clients[i] == null){
						slot = i;
						break;
					}
				}
				if (slot == -1) reason = "full";
			}
			if (reason == null){
				clients[slot] = c;
				c.slot = slot;
				CoopNet.Player p = new CoopNet.Player();
				p.slot = slot;
				String name = m.getString( "name" );
				p.name = name.isEmpty() ? "Player " + (slot+1) : name;
				players.add( p );
			}
		}
		if (reason != null){
			Bundle reject = NetConnection.message( CoopNet.REJECT );
			reject.put( "reason", reason );
			c.send( reject );
			c.close( "rejected: " + reason );
			return;
		}
		Bundle welcome = NetConnection.message( CoopNet.WELCOME );
		welcome.put( "slot", slot );
		c.send( welcome );
		lobbyChanged();
	}

	@Override
	public void onClosed( NetConnection c, String reason ){
		String name = null;
		int slot = c.slot;
		synchronized (this){
			if (slot > 0 && clients[slot] == c){
				clients[slot] = null;
				CoopNet.Player p = player( slot );
				if (p != null){
					name = p.name;
					players.remove( p );
				}
			}
		}
		if (name != null){
			events.onPlayerLeft( slot, name, reason );
			lobbyChanged();
		}
	}

	private CoopNet.Player player( int slot ){
		for (CoopNet.Player p : players) if (p.slot == slot) return p;
		return null;
	}

	private final Object lobbyLock = new Object();
	private int lobbySeq = 0;

	//one lobby update at a time, numbered: updates from different client threads must not
	//overtake each other, or a client could end up showing an older lobby
	private void lobbyChanged(){
		synchronized (lobbyLock){
			Bundle lobby = NetConnection.message( CoopNet.LOBBY );
			List<CoopNet.Player> snapshot = players();
			CoopNet.storePlayers( lobby, snapshot );
			lobby.put( "seq", ++lobbySeq );
			broadcast( lobby );
			events.onLobbyChanged( snapshot );
		}
	}

	//the host's own choices in the lobby
	public void setHostClass( String cls ){
		synchronized (this){
			players.get(0).heroClass = cls;
		}
		lobbyChanged();
	}

	public void setHostReady( boolean ready ){
		synchronized (this){
			players.get(0).ready = ready;
		}
		lobbyChanged();
	}

	public synchronized boolean allReady(){
		for (CoopNet.Player p : players) if (!p.ready) return false;
		return players.size() > 1;
	}

	//closes the lobby and tells every client the run starts
	public boolean startGame(){
		synchronized (this){
			if (started || !allReady()) return false;
			started = true;
		}
		beacon.stop();
		Bundle start = NetConnection.message( CoopNet.START );
		CoopNet.storePlayers( start, players() );
		broadcast( start );
		return true;
	}

	public void broadcast( Bundle message ){
		for (NetConnection c : connections()) c.send( message );
	}

	public boolean send( int slot, Bundle message ){
		NetConnection c;
		synchronized (this){
			c = slot > 0 && slot < clients.length ? clients[slot] : null;
		}
		return c != null && c.send( message );
	}

	public void stop(){
		running = false;
		beacon.stop();
		Bundle bye = NetConnection.message( CoopNet.BYE );
		bye.put( "reason", "host closed" );
		for (NetConnection c : connections()){
			c.send( bye );
			c.close( "host closed" );
		}
		try {
			if (server != null) server.close();
		} catch (IOException ignored){
		}
	}
}
