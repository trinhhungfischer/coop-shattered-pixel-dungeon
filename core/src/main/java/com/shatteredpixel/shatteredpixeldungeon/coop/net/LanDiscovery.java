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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Finding games on the local network: a hosting game broadcasts a small UDP beacon every second,
 * a joining game listens for them. Joining by IP address works without this.
 *
 * Beacon text: SPDCOOP|protocol|tcpPort|hostName|players|maxPlayers
 */
public class LanDiscovery {

	private static final String MAGIC = "SPDCOOP";

	//tests point both sides at the loopback address instead of the LAN broadcast address
	public static volatile InetAddress target = null;
	public static volatile int port = CoopNet.DISCOVERY_PORT;
	//tests listen on the loopback address only
	public static volatile InetAddress listenAddress = null;

	public static class Game {
		public String address;
		public int port;
		public String name;
		public int players;
		public int maxPlayers;
		public long lastSeen;
	}

	public static class Beacon {

		private final CoopHost host;
		private volatile boolean running = false;
		private DatagramSocket socket;

		Beacon( CoopHost host ){
			this.host = host;
		}

		void start(){
			if (running) return;
			try {
				socket = new DatagramSocket();
				socket.setBroadcast( true );
			} catch (IOException e){
				return;
			}
			running = true;
			Thread t = new Thread( this::loop, "coop-lan-beacon" );
			t.setDaemon( true );
			t.start();
		}

		private void loop(){
			while (running){
				try {
					String text = MAGIC + "|" + CoopNet.PROTOCOL + "|" + host.port() + "|"
							+ host.hostName().replace("|", "/") + "|" + host.playerCount() + "|" + CoopNet.MAX_PLAYERS;
					byte[] data = text.getBytes( StandardCharsets.UTF_8 );
					InetAddress to = target != null ? target : InetAddress.getByName( "255.255.255.255" );
					socket.send( new DatagramPacket( data, data.length, to, port ) );
					Thread.sleep( 1000 );
				} catch (InterruptedException e){
					return;
				} catch (IOException ignored){
					try { Thread.sleep( 1000 ); } catch (InterruptedException e){ return; }
				}
			}
		}

		void stop(){
			running = false;
			if (socket != null) socket.close();
		}
	}

	public static class Finder {

		private final LinkedHashMap<String, Game> found = new LinkedHashMap<>();
		private volatile boolean running = false;
		private DatagramSocket socket;

		public boolean start(){
			try {
				socket = new DatagramSocket( null );
				socket.setReuseAddress( true );
				socket.setSoTimeout( 500 );
				socket.bind( listenAddress != null ? new InetSocketAddress( listenAddress, port ) : new InetSocketAddress( port ) );
			} catch (IOException e){
				return false;
			}
			running = true;
			Thread t = new Thread( this::loop, "coop-lan-finder" );
			t.setDaemon( true );
			t.start();
			return true;
		}

		private void loop(){
			byte[] buf = new byte[512];
			while (running){
				try {
					DatagramPacket p = new DatagramPacket( buf, buf.length );
					socket.receive( p );
					String[] parts = new String( p.getData(), 0, p.getLength(), StandardCharsets.UTF_8 ).split( "\\|" );
					if (parts.length < 6 || !parts[0].equals( MAGIC )) continue;
					if (Integer.parseInt( parts[1] ) != CoopNet.PROTOCOL) continue;
					Game g = new Game();
					g.address = p.getAddress().getHostAddress();
					g.port = Integer.parseInt( parts[2] );
					g.name = parts[3];
					g.players = Integer.parseInt( parts[4] );
					g.maxPlayers = Integer.parseInt( parts[5] );
					g.lastSeen = System.currentTimeMillis();
					synchronized (found){
						found.put( g.address + ":" + g.port, g );
					}
				} catch (SocketTimeoutException ignored){
				} catch (IOException | NumberFormatException e){
					if (!running) return;
				}
			}
		}

		//games heard from in the last few seconds
		public List<Game> games(){
			long now = System.currentTimeMillis();
			ArrayList<Game> list = new ArrayList<>();
			synchronized (found){
				found.values().removeIf( g -> now - g.lastSeen > 4000 );
				list.addAll( found.values() );
			}
			return list;
		}

		public void stop(){
			running = false;
			if (socket != null) socket.close();
		}
	}
}
