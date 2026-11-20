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

import java.util.ArrayList;
import java.util.List;

/**
 * Shared constants and the lobby model of online coop (docs/coop-online-design.md, M2).
 *
 * Lobby protocol (message type under "t"):
 *  client -> host: hello {protocol, game, name}, pick {cls}, ready {ready}, ping {at}, pong {at}, bye
 *  host -> client: welcome {slot}, reject {reason}, lobby {players...}, start, ping {at}, pong {at}, bye {reason}
 */
public class CoopNet {

	//bump when messages change in an incompatible way
	public static final int PROTOCOL = 1;

	public static final int DEFAULT_PORT = 22334;
	public static final int DISCOVERY_PORT = 22335;

	public static final int MAX_PLAYERS = 4;
	public static final int MAX_MESSAGE = 8 * 1024 * 1024;

	public static final long HEARTBEAT_MS = 2000;
	public static final long TIMEOUT_MS = 10000;

	//message types
	public static final String HELLO   = "hello";
	public static final String WELCOME = "welcome";
	public static final String REJECT  = "reject";
	public static final String LOBBY   = "lobby";
	public static final String PICK    = "pick";
	public static final String READY   = "ready";
	public static final String START   = "start";
	public static final String PING    = "ping";
	public static final String PONG    = "pong";
	public static final String BYE     = "bye";

	public static class Player {
		public int slot;
		public String name = "";
		public String heroClass = "WARRIOR";
		public boolean ready;
		//round trip time in ms as last measured by the host, -1 unknown
		public int ping = -1;

		public Player copy(){
			Player p = new Player();
			p.slot = slot;
			p.name = name;
			p.heroClass = heroClass;
			p.ready = ready;
			p.ping = ping;
			return p;
		}
	}

	public static void storePlayers( Bundle b, List<Player> players ){
		int n = players.size();
		int[] slots = new int[n];
		String[] names = new String[n];
		String[] classes = new String[n];
		boolean[] ready = new boolean[n];
		int[] pings = new int[n];
		for (int i = 0; i < n; i++){
			Player p = players.get(i);
			slots[i] = p.slot;
			names[i] = p.name;
			classes[i] = p.heroClass;
			ready[i] = p.ready;
			pings[i] = p.ping;
		}
		b.put( "slots", slots );
		b.put( "names", names );
		b.put( "classes", classes );
		b.put( "ready", ready );
		b.put( "pings", pings );
	}

	public static List<Player> restorePlayers( Bundle b ){
		ArrayList<Player> players = new ArrayList<>();
		int[] slots = b.getIntArray( "slots" );
		String[] names = b.getStringArray( "names" );
		String[] classes = b.getStringArray( "classes" );
		boolean[] ready = b.getBooleanArray( "ready" );
		int[] pings = b.getIntArray( "pings" );
		if (slots == null) return players;
		for (int i = 0; i < slots.length; i++){
			Player p = new Player();
			p.slot = slots[i];
			p.name = names != null && i < names.length ? names[i] : "";
			p.heroClass = classes != null && i < classes.length ? classes[i] : "WARRIOR";
			p.ready = ready != null && i < ready.length && ready[i];
			p.ping = pings != null && i < pings.length ? pings[i] : -1;
			players.add( p );
		}
		return players;
	}
}
