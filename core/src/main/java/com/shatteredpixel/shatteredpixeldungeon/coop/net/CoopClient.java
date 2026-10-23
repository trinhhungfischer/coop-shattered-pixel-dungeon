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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * The joining side: connects to a host, takes part in the lobby and receives game messages.
 * Callbacks come from the network thread.
 */
public class CoopClient implements NetConnection.Listener {

	public interface Events {
		default void onWelcome( int slot ){}
		default void onRejected( String reason ){}
		default void onLobbyChanged( List<CoopNet.Player> players ){}
		default void onStart( List<CoopNet.Player> players ){}
		default void onDisconnected( String reason ){}
		//any message that isn't part of the lobby protocol (game state from M3 on)
		default void onGameMessage( Bundle message ){}
	}

	private final int gameVersion;
	private final Events events;

	private NetConnection connection;
	private volatile int slot = -1;
	private volatile int ping = -1;
	private volatile List<CoopNet.Player> players = new ArrayList<>();
	private volatile boolean running = false;
	private int lobbySeq = 0;

	public CoopClient( int gameVersion, Events events ){
		this.gameVersion = gameVersion;
		this.events = events;
	}

	public void connect( String host, int port, String name, int timeoutMs ) throws IOException {
		Socket s = new Socket();
		s.connect( new InetSocketAddress( host, port ), timeoutMs );
		connection = new NetConnection( s, this );
		running = true;
		connection.start( "coop-client" );

		Bundle hello = NetConnection.message( CoopNet.HELLO );
		hello.put( "protocol", CoopNet.PROTOCOL );
		hello.put( "game", gameVersion );
		hello.put( "name", name );
		connection.send( hello );

		Thread heartbeat = new Thread( this::heartbeatLoop, "coop-client-heartbeat" );
		heartbeat.setDaemon( true );
		heartbeat.start();
	}

	private void heartbeatLoop(){
		while (running && connection.isOpen()){
			try {
				Thread.sleep( CoopNet.HEARTBEAT_MS );
			} catch (InterruptedException e){
				return;
			}
			long now = System.currentTimeMillis();
			if (now - connection.lastReceived() > CoopNet.TIMEOUT_MS){
				connection.close( "host timed out" );
				return;
			}
			Bundle p = NetConnection.message( CoopNet.PING );
			p.put( "at", now );
			connection.send( p );
		}
	}

	public int slot(){
		return slot;
	}

	public int ping(){
		return ping;
	}

	public List<CoopNet.Player> players(){
		return players;
	}

	public boolean isConnected(){
		return connection != null && connection.isOpen();
	}

	public void pick( String heroClass ){
		Bundle m = NetConnection.message( CoopNet.PICK );
		m.put( "cls", heroClass );
		send( m );
	}

	public void setReady( boolean ready ){
		Bundle m = NetConnection.message( CoopNet.READY );
		m.put( "ready", ready );
		send( m );
	}

	public boolean send( Bundle message ){
		return connection != null && connection.send( message );
	}

	public void disconnect(){
		running = false;
		if (connection != null){
			connection.send( NetConnection.message( CoopNet.BYE ) );
			connection.close( "left" );
		}
	}

	@Override
	public void onMessage( NetConnection c, Bundle m ){
		switch (NetConnection.type( m )){
			case CoopNet.WELCOME:
				slot = m.getInt( "slot" );
				events.onWelcome( slot );
				return;
			case CoopNet.REJECT:
				events.onRejected( m.getString( "reason" ) );
				return;
			case CoopNet.LOBBY:
				int seq = m.getInt( "seq" );
				if (seq < lobbySeq) return; //an older update that arrived late
				lobbySeq = seq;
				players = CoopNet.restorePlayers( m );
				events.onLobbyChanged( players );
				return;
			case CoopNet.START:
				players = CoopNet.restorePlayers( m );
				events.onStart( players );
				return;
			case CoopNet.PING:
				Bundle pong = NetConnection.message( CoopNet.PONG );
				pong.put( "at", m.getLong( "at" ) );
				c.send( pong );
				return;
			case CoopNet.PONG:
				ping = (int)(System.currentTimeMillis() - m.getLong( "at" ));
				return;
			case CoopNet.BYE:
				c.close( m.getString( "reason" ) );
				return;
			default:
				events.onGameMessage( m );
		}
	}

	@Override
	public void onClosed( NetConnection c, String reason ){
		running = false;
		events.onDisconnected( reason );
	}
}
