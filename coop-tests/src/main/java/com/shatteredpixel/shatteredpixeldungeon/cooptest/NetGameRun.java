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

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs NetGameSim as two child processes (host and client) and reports both.
 * Args: [target depth] [number of clients] [deaths]
 */
public class NetGameRun {

	public static void main( String[] args ) throws Exception {
		String depth = args.length > 0 ? args[0] : "4";
		int clients = args.length > 1 ? Integer.parseInt( args[1] ) : 1;
		String mode = args.length > 2 ? args[2] : "items";
		int port;
		try (ServerSocket s = new ServerSocket( 0 )){
			port = s.getLocalPort();
		}
		String java = System.getProperty( "java.home" ) + File.separator + "bin" + File.separator + "java";
		String cp = System.getProperty( "java.class.path" );
		String main = NetGameSim.class.getName();

		Process host = new ProcessBuilder( java, "-cp", cp, main, "host", Integer.toString( port ), depth, Integer.toString( clients ), mode ).redirectErrorStream( true ).start();
		List<String> hostOut = collect( host );
		List<Process> clientProcs = new ArrayList<>();
		List<List<String>> clientOuts = new ArrayList<>();
		for (int i = 1; i <= clients; i++){
			Process c = new ProcessBuilder( java, "-cp", cp, main, "client", Integer.toString( port ), Integer.toString( i ), mode ).redirectErrorStream( true ).start();
			clientProcs.add( c );
			clientOuts.add( collect( c ) );
		}

		int h = host.waitFor();
		StringBuilder exits = new StringBuilder();
		boolean ok = h == 0;
		for (Process c : clientProcs){
			int e = c.waitFor();
			ok = ok && e == 0;
			exits.append( ", client exit " ).append( e );
		}
		for (String l : hostOut) System.out.println( l );
		for (int i = 0; i < clientOuts.size(); i++){
			System.out.println( "---- client " + (i + 1) );
			for (String l : clientOuts.get( i )) System.out.println( l );
		}
		System.out.println( ok ? "[NETGAME] PASSED" : "[NETGAME] FAILED (host exit " + h + exits + ")" );
		System.exit( ok ? 0 : 1 );
	}

	private static List<String> collect( Process p ){
		List<String> lines = new ArrayList<>();
		Thread t = new Thread( () -> {
			try (BufferedReader r = new BufferedReader( new InputStreamReader( p.getInputStream() ) )){
				String l;
				while ((l = r.readLine()) != null){
					if (l.startsWith( "[HOST]" ) || l.startsWith( "[CLIENT" ) || l.startsWith( "[SIM]" )){
						synchronized (lines){ lines.add( l.length() > 400 && !l.contains( "PROBLEM" ) ? l.substring( 0, 400 ) : l ); }
					}
				}
			} catch (Exception ignored){
			}
		});
		t.setDaemon( true );
		t.start();
		return lines;
	}
}
