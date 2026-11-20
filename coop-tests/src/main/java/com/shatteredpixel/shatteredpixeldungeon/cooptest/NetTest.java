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

package com.shatteredpixel.shatteredpixeldungeon.cooptest;

import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopClient;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopHost;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopNet;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.LanDiscovery;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.NetConnection;
import com.watabou.utils.Bundle;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

/**
 * Tests the online coop network layer on the loopback address (no firewall prompts, no window).
 *   NetTest                 all checks in one process
 *   NetTest host <port> <n> a host process waiting for n clients, then starting
 *   NetTest client <port>   a client process joining it
 */
public class NetTest {

	static final int GAME = 922;
	static final List<String> problems = new ArrayList<>();

	public static void main( String[] args ) throws Exception {
		if (args.length >= 3 && args[0].equals("host")){
			System.exit( hostProcess( Integer.parseInt(args[1]), Integer.parseInt(args[2]) ) ? 0 : 1 );
		}
		if (args.length >= 2 && args[0].equals("client")){
			System.exit( clientProcess( Integer.parseInt(args[1]) ) ? 0 : 1 );
		}
		inProcess();
		System.out.println( problems.isEmpty() ? "[NET] PASSED" : "[NET] FAILED with " + problems.size() + " problem(s)" );
		System.exit( problems.isEmpty() ? 0 : 1 );
	}

	static void check( boolean ok, String what ){
		System.out.println( (ok ? "[NET] ok   " : "[NET] FAIL ") + what );
		if (!ok) problems.add( what );
	}

	static boolean waitFor( BooleanSupplier cond, long ms ){
		long end = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < end){
			if (cond.getAsBoolean()) return true;
			try { Thread.sleep( 20 ); } catch (InterruptedException e){ return false; }
		}
		return cond.getAsBoolean();
	}

	static class HostLog implements CoopHost.Events {
		volatile List<CoopNet.Player> lobby = new ArrayList<>();
		final ConcurrentLinkedQueue<String> left = new ConcurrentLinkedQueue<>();
		final ConcurrentLinkedQueue<Bundle> game = new ConcurrentLinkedQueue<>();
		final ConcurrentLinkedQueue<Integer> gameSlots = new ConcurrentLinkedQueue<>();
		@Override public void onLobbyChanged( List<CoopNet.Player> players ){ lobby = players; }
		@Override public void onPlayerLeft( int slot, String name, String reason ){ left.add( name ); }
		@Override public void onGameMessage( int slot, Bundle message ){ gameSlots.add( slot ); game.add( message ); }
	}

	static class ClientLog implements CoopClient.Events {
		volatile int slot = -1;
		volatile String rejected = null;
		volatile String disconnected = null;
		volatile List<CoopNet.Player> lobby = new ArrayList<>();
		volatile List<CoopNet.Player> started = null;
		final ConcurrentLinkedQueue<Bundle> game = new ConcurrentLinkedQueue<>();
		@Override public void onWelcome( int s ){ slot = s; }
		@Override public void onRejected( String reason ){ rejected = reason; }
		@Override public void onLobbyChanged( List<CoopNet.Player> players ){ lobby = players; }
		@Override public void onStart( List<CoopNet.Player> players ){ started = players; }
		@Override public void onDisconnected( String reason ){ disconnected = reason; }
		@Override public void onGameMessage( Bundle message ){ game.add( message ); }
	}

	static void inProcess() throws Exception {
		InetAddress lo = InetAddress.getByName( "127.0.0.1" );

		HostLog hl = new HostLog();
		CoopHost host = new CoopHost( GAME, "Host", "WARRIOR", hl );
		host.start( lo, 0, false );
		int port = host.port();
		check( port > 0, "host listens on a port (" + port + ")" );

		//three clients fill the lobby
		ClientLog[] cl = new ClientLog[3];
		CoopClient[] clients = new CoopClient[3];
		for (int i = 0; i < 3; i++){
			cl[i] = new ClientLog();
			clients[i] = new CoopClient( GAME, cl[i] );
			clients[i].connect( "127.0.0.1", port, "Client" + (i+1), 3000 );
		}
		check( waitFor( () -> cl[0].slot > 0 && cl[1].slot > 0 && cl[2].slot > 0, 3000 ), "three clients get slots" );
		check( cl[0].slot != cl[1].slot && cl[1].slot != cl[2].slot && cl[0].slot != cl[2].slot, "slots are distinct" );
		check( waitFor( () -> hl.lobby.size() == 4 && cl[2].lobby.size() == 4, 3000 ), "lobby shows four players on host and clients" );

		//a fifth player is turned away
		ClientLog full = new ClientLog();
		new CoopClient( GAME, full ).connect( "127.0.0.1", port, "TooMany", 3000 );
		check( waitFor( () -> "full".equals( full.rejected ), 3000 ), "a fifth player is rejected (full)" );

		//other game versions are turned away
		ClientLog old = new ClientLog();
		new CoopClient( GAME - 1, old ).connect( "127.0.0.1", port, "Old", 3000 );
		check( waitFor( () -> "version".equals( old.rejected ), 3000 ), "a different game version is rejected" );

		//class picks and ready flags reach everyone
		clients[0].pick( "MAGE" );
		clients[1].pick( "ROGUE" );
		clients[2].pick( "HUNTRESS" );
		check( waitFor( () -> classOf( cl[1].lobby, cl[0].slot ).equals( "MAGE" ) && classOf( hl.lobby, cl[2].slot ).equals( "HUNTRESS" ), 3000 ),
				"class picks reach host and other clients" );
		for (CoopClient c : clients) c.setReady( true );
		check( waitFor( () -> readyCount( hl.lobby ) == 3, 3000 ), "ready flags reach the host" );
		check( !host.allReady() && !host.startGame(), "cannot start while the host isn't ready" );
		host.setHostReady( true );
		check( waitFor( host::allReady, 3000 ), "everyone ready" );

		//game messages both ways, including a large one
		Bundle cmd = NetConnection.message( "cmd" );
		cmd.put( "cell", 123 );
		clients[1].send( cmd );
		check( waitFor( () -> !hl.game.isEmpty(), 3000 ) && hl.game.peek().getInt( "cell" ) == 123
				&& hl.gameSlots.peek() == cl[1].slot, "client -> host game message, tagged with its slot" );

		Bundle big = NetConnection.message( "snapshot" );
		int[] data = new int[500_000];
		for (int i = 0; i < data.length; i++) data[i] = i * 7;
		big.put( "data", data );
		host.send( cl[2].slot, big );
		check( waitFor( () -> !cl[2].game.isEmpty(), 5000 ) && cl[2].game.peek().getIntArray( "data" )[499_999] == 499_999 * 7,
				"host -> client large message (2 MB of ints)" );
		check( cl[0].game.isEmpty(), "a message to one slot only reaches that client" );

		//start the game
		check( host.startGame(), "host starts the game" );
		check( waitFor( () -> cl[0].started != null && cl[2].started != null && cl[1].started.size() == 4, 3000 ), "every client gets the start" );
		ClientLog late = new ClientLog();
		new CoopClient( GAME, late ).connect( "127.0.0.1", port, "Late", 3000 );
		check( waitFor( () -> "started".equals( late.rejected ), 3000 ), "joining after the start is rejected" );

		//heartbeats measure ping
		check( waitFor( () -> clients[0].ping() >= 0, 6000 ), "client measured a ping (" + clients[0].ping() + " ms)" );

		//leaving
		clients[1].disconnect();
		check( waitFor( () -> hl.left.contains( "Client2" ), 3000 ), "host notices a client leaving" );
		check( waitFor( () -> hl.lobby.size() == 3, 3000 ), "lobby drops the player who left" );

		host.stop();
		check( waitFor( () -> cl[0].disconnected != null && cl[2].disconnected != null, 3000 ), "clients notice the host closing" );

		//LAN discovery over loopback
		LanDiscovery.target = lo;
		LanDiscovery.port = 22999;
		LanDiscovery.Finder finder = new LanDiscovery.Finder();
		check( finder.start(), "LAN finder listens" );
		CoopHost lanHost = new CoopHost( GAME, "LanHost", "MAGE", new HostLog() );
		lanHost.start( lo, 0, true );
		check( waitFor( () -> !finder.games().isEmpty() && finder.games().get(0).name.equals( "LanHost" )
				&& finder.games().get(0).port == lanHost.port(), 4000 ), "LAN finder sees the hosted game" );
		lanHost.stop();
		finder.stop();
	}

	static String classOf( List<CoopNet.Player> lobby, int slot ){
		for (CoopNet.Player p : lobby) if (p.slot == slot) return p.heroClass;
		return "";
	}

	static int readyCount( List<CoopNet.Player> lobby ){
		int n = 0;
		for (CoopNet.Player p : lobby) if (p.ready) n++;
		return n;
	}

	//separate processes: the host waits for n clients to be ready, starts, and expects a message from each
	static boolean hostProcess( int port, int n ) throws Exception {
		HostLog hl = new HostLog();
		CoopHost host = new CoopHost( GAME, "Host", "WARRIOR", hl );
		host.start( InetAddress.getByName( "127.0.0.1" ), port, false );
		System.out.println( "[NET] host process listening on " + host.port() );
		host.setHostReady( true );
		boolean ok = waitFor( () -> hl.lobby.size() == n + 1 && host.allReady(), 20000 );
		check( ok, "host process: " + n + " client processes joined and got ready" );
		ok &= host.startGame();
		ok &= waitFor( () -> hl.game.size() >= n, 10000 );
		check( ok, "host process: started and got a game message from every client" );
		Bundle bye = NetConnection.message( "done" );
		host.broadcast( bye );
		Thread.sleep( 500 );
		host.stop();
		return ok && problems.isEmpty();
	}

	static boolean clientProcess( int port ) throws Exception {
		ClientLog cl = new ClientLog();
		CoopClient client = new CoopClient( GAME, cl );
		boolean connected = false;
		for (int i = 0; i < 50 && !connected; i++){
			try {
				client.connect( "127.0.0.1", port, "Proc" + ProcessHandle.current().pid(), 1000 );
				connected = true;
			} catch (Exception e){
				Thread.sleep( 200 );
			}
		}
		check( connected && waitFor( () -> cl.slot > 0, 5000 ), "client process joined (slot " + cl.slot + ")" );
		client.pick( "ROGUE" );
		client.setReady( true );
		check( waitFor( () -> cl.started != null, 20000 ), "client process saw the start" );
		Bundle hi = NetConnection.message( "cmd" );
		hi.put( "from", cl.slot );
		client.send( hi );
		check( waitFor( () -> !cl.game.isEmpty(), 10000 ), "client process got the host's message" );
		client.disconnect();
		return problems.isEmpty();
	}
}
