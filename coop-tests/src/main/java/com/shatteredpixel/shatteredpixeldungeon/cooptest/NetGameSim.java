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

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Invulnerability;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopClient;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopHost;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopNet;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopOnline;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopOnlineGame;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopRemote;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import com.watabou.utils.BArray;
import com.watabou.utils.PathFinder;

import java.net.InetAddress;
import java.util.List;

/**
 * Online coop end to end, two headless game processes on the loopback address:
 *   NetGameSim host <port> <depth>   runs the real game, its bot plays the host's hero
 *   NetGameSim client <port>         mirrors the host's game, its bot plays the client's hero
 * The client checks after every update that its mirror matches the host (char ids, positions,
 * HP). The host ends the session at the target depth; both report PASSED or FAILED.
 */
public class NetGameSim {

	static boolean isHost;
	static int port;
	static int targetDepth = 3;
	static int frames = 0;
	static int phase = 0;
	static int actions = 0;
	static long lastProgress = System.currentTimeMillis();
	static int lastDepth = 0;

	public static void main( String[] args ) throws Exception {
		isHost = args[0].equals( "host" );
		port = Integer.parseInt( args[1] );
		if (args.length > 2) targetDepth = Integer.parseInt( args[2] );
		CoopSim.customDriver = NetGameSim::frame;
		CoopSim.launch();
	}

	static void log( String s ){
		System.out.println( (isHost ? "[HOST] " : "[CLIENT] ") + s );
	}

	static void frame(){
		frames++;
		try {
			if (isHost) hostFrame(); else clientFrame();
		} catch (Exception e){
			CoopSim.problem( (isHost ? "host" : "client") + " driver crashed: " + CoopSim.stack( e ) );
			CoopSim.finish();
		}
		if (System.currentTimeMillis() - lastProgress > 120_000){
			CoopSim.problem( (isHost ? "host" : "client") + " made no progress for 2 minutes in phase " + phase
					+ " depth " + Dungeon.depth + (Dungeon.hero != null ? " pos " + Dungeon.hero.pos : "") + " | " + CoopSim.Driver.actorDump() );
			CoopSim.finish();
		}
	}

	// ---------------------------------------------------------------------------------
	// Host
	// ---------------------------------------------------------------------------------

	static void hostFrame() throws Exception {
		switch (phase){
			case 0:
				if (frames < 10) return;
				CoopHost host = new CoopHost( Game.versionCode, "Host", "WARRIOR", new CoopHost.Events(){} );
				host.start( InetAddress.getByName( "127.0.0.1" ), port, false );
				CoopOnline.leave();
				CoopOnline.role = CoopOnline.Role.HOST;
				CoopOnline.host = host;
				host.setHostReady( true );
				log( "hosting on " + port );
				phase = 1;
				return;
			case 1:
				if (!CoopOnline.host.allReady()) return;
				CoopOnline.host.startGame();
				CoopOnline.players = CoopOnline.host.players();
				GamesInProgress.curSlot = 1;
				CoopOnlineGame.start();
				log( "started with " + CoopOnline.players.size() + " players" );
				lastProgress = System.currentTimeMillis();
				phase = 2;
				return;
			case 2:
				if (!(Game.scene() instanceof GameScene) || Dungeon.hero == null || !CoopRemote.onlineHost()) return;
				if (Dungeon.depth != lastDepth){
					lastDepth = Dungeon.depth;
					lastProgress = System.currentTimeMillis();
					log( "now on depth " + Dungeon.depth );
				}
				if (Dungeon.depth >= targetDepth){
					log( "reached depth " + Dungeon.depth + " after " + actions + " actions, closing the session" );
					CoopOnline.leave();
					CoopSim.finish();
					return;
				}
				cheats();
				Hero me = CoopRemote.localHero();
				if (frames % 2000 == 0){
					StringBuilder sb = new StringBuilder("state:");
					for (Hero h : Coop.heroes()) sb.append(" P").append(Coop.indexOf(h)+1).append(" pos=").append(h.pos)
							.append(" ready=").append(h.ready).append(" act=").append(h.curAction == null ? "-" : h.curAction.getClass().getSimpleName())
							.append(" waiting=").append(com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime.isWaiting(h))
							.append(" t=").append(Actor.coopTime(h));
					sb.append(" processing=").append(Actor.processing()).append(" inputReady=").append(CoopRemote.inputReady())
							.append(" dungeonHero=P").append(Coop.indexOf(Dungeon.hero)+1).append(" actions=").append(actions);
					log( sb.toString() );
				}
				if (!CoopRemote.inputReady()) return;
				if (frames % 3 != 0) return;
				int cell = chooseCell( me );
				if (cell != -1){
					CoopRemote.onCellSelected( cell );
					actions++;
				}
		}
	}

	//test cheats on the real game: heroes can't die and everyone knows the map
	static void cheats(){
		for (Hero h : Coop.aliveHeroes()) Buff.prolong( h, Invulnerability.class, 5f );
	}

	// ---------------------------------------------------------------------------------
	// Client
	// ---------------------------------------------------------------------------------

	static CoopClient client;
	static volatile boolean started = false;
	static volatile List<CoopNet.Player> startPlayers;

	static void clientFrame() throws Exception {
		CoopRemote.clientFrame();
		switch (phase){
			case 0:
				if (frames < 10) return;
				client = new CoopClient( Game.versionCode, new CoopClient.Events() {
					@Override public void onWelcome( int slot ){ CoopOnline.localSlot = slot; }
					@Override public void onStart( List<CoopNet.Player> players ){ startPlayers = players; started = true; }
				});
				for (int i = 0; i < 100; i++){
					try {
						client.connect( "127.0.0.1", port, "Client", 1000 );
						break;
					} catch (Exception e){
						Thread.sleep( 200 );
					}
				}
				CoopOnline.leave();
				CoopOnline.role = CoopOnline.Role.CLIENT;
				CoopOnline.client = client;
				client.pick( "ROGUE" );
				client.setReady( true );
				phase = 1;
				return;
			case 1:
				if (!started) return;
				CoopOnline.players = startPlayers;
				CoopOnlineGame.start();
				log( "host started, waiting for the snapshot" );
				lastProgress = System.currentTimeMillis();
				phase = 2;
				return;
			case 2:
				if (!CoopOnline.isClient()){
					//the host closed the session
					log( "session closed by the host: " + CoopRemote.updatesApplied + " updates applied, "
							+ CoopRemote.checksumMismatches + " checksum mismatches, last depth " + Dungeon.depth );
					if (CoopRemote.updatesApplied < 30) CoopSim.problem( "client applied only " + CoopRemote.updatesApplied + " updates" );
					if (CoopRemote.checksumMismatches > 0) CoopSim.problem( "mirror differed from the host after " + CoopRemote.checksumMismatches + " updates; last: " + CoopRemote.lastMismatch );
					if (lastDepth < 2) CoopSim.problem( "client never followed the party down (depth " + lastDepth + ")" );
					CoopSim.finish();
					return;
				}
				if (!(Game.scene() instanceof GameScene) || !CoopRemote.onlineClient() || Dungeon.hero == null) return;
				if (Dungeon.depth != lastDepth){
					lastDepth = Dungeon.depth;
					lastProgress = System.currentTimeMillis();
					log( "mirror on depth " + Dungeon.depth + " as " + Dungeon.hero.heroClass + " (party index " + CoopRemote.localPartyIndex() + ")" );
					if (Dungeon.hero.heroClass != HeroClass.ROGUE) CoopSim.problem( "client controls a " + Dungeon.hero.heroClass + ", expected its Rogue" );
				}
				if (!CoopRemote.inputReady()) return;
				if (frames % 3 != 0) return;
				int cell = chooseCell( Dungeon.hero );
				if (cell != -1){
					CoopRemote.onCellSelected( cell );
					actions++;
				}
		}
	}

	// ---------------------------------------------------------------------------------
	// Bot: fight what's next to you, otherwise step towards the stairs (one tile at a time,
	// so it works on the client's mirror too)
	// ---------------------------------------------------------------------------------

	static int lastPos = -1;
	static int stuck = 0;

	static int chooseCell( Hero me ){
		Level level = Dungeon.level;
		for (Mob m : level.mobs){
			if (m.alignment == Char.Alignment.ENEMY && level.adjacent( me.pos, m.pos )) return m.pos;
		}
		LevelTransition exit = level.getTransition( LevelTransition.Type.REGULAR_EXIT );
		if (exit == null || exit.type != LevelTransition.Type.REGULAR_EXIT) return me.pos;
		if (exit.inside( me.pos )){
			//on the stairs: go down once everyone is close, otherwise wait a turn (toolbar wait)
			int range = com.shatteredpixel.shatteredpixeldungeon.coop.CoopCore.transitionRange( level, exit );
			for (Hero o : Coop.aliveHeroes()){
				if (o != me && level.distance( o.pos, exit.cell() ) > range){
					me.rest( false );
					return -1;
				}
			}
			return exit.cell();
		}
		//no pits or known traps: stepping into a chasm asks for confirmation and the move is dropped
		boolean[] pass = level.passable.clone();
		for (int i = 0; i < pass.length; i++) if (level.avoid[i] || level.pit[i]) pass[i] = false;
		pass[exit.cell()] = true;
		PathFinder.buildDistanceMap( exit.cell(), pass );
		int best = -1;
		for (int i : PathFinder.NEIGHBOURS8){
			int c = me.pos + i;
			if (c < 0 || c >= level.length() || PathFinder.distance[c] == Integer.MAX_VALUE) continue;
			Actor a = Actor.findChar( c );
			//don't walk into teammates (that would swap back and forth), except on the stairs
			if (a != null && !(a instanceof Hero && exit.inside( c ))) continue;
			if (best == -1 || PathFinder.distance[c] < PathFinder.distance[best]) best = c;
		}
		//blocked for a while (a teammate or a mob in a corridor): step aside somewhere
		if (me.pos == lastPos) stuck++; else stuck = 0;
		lastPos = me.pos;
		if (stuck > 6){
			stuck = 0;
			int start = (actions * 5) % 8;
			for (int k = 0; k < 8; k++){
				int c = me.pos + PathFinder.NEIGHBOURS8[(start + k) % 8];
				if (c >= 0 && c < level.length() && pass[c] && Actor.findChar( c ) == null) return c;
			}
		}
		if (best == -1 || PathFinder.distance[best] >= PathFinder.distance[me.pos]) return me.pos;
		return best;
	}
}
