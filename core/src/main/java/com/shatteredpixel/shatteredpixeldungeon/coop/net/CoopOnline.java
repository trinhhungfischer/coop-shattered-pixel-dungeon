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

import java.util.ArrayList;
import java.util.List;

/**
 * The online session this game is part of: none, hosting, or joined to a host.
 * The lobby fills it in; the game side (M3/M4) reads it.
 */
public class CoopOnline {

	public enum Role { NONE, HOST, CLIENT }

	public static volatile Role role = Role.NONE;
	public static volatile CoopHost host;
	public static volatile CoopClient client;

	//this machine's player slot (the host is 0) and the players at the start of the run, by slot
	public static volatile int localSlot = 0;
	public static volatile List<CoopNet.Player> players = new ArrayList<>();

	public static boolean active(){
		return role != Role.NONE;
	}

	public static boolean isHost(){
		return role == Role.HOST;
	}

	public static boolean isClient(){
		return role == Role.CLIENT;
	}

	public static void leave(){
		if (host != null) host.stop();
		if (client != null) client.disconnect();
		host = null;
		client = null;
		role = Role.NONE;
		localSlot = 0;
		players = new ArrayList<>();
	}
}
