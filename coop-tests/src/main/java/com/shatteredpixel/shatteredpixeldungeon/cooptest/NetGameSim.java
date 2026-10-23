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
				if (!gaveScroll && Coop.size() > 1 && !Actor.processing()){
					gaveScroll = true;
					Coop.get(1).belongings.backpack.items.add( new com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfUpgrade().identify() );
					log( "gave the client's hero a scroll of upgrade" );
				}
				Hero me = CoopRemote.localHero();
				if (frames % 2000 == 0){
					StringBuilder sb = new StringBuilder("state:");
					for (Hero h : Coop.heroes()) sb.append(" P").append(Coop.indexOf(h)+1).append(" pos=").append(h.pos)
							.append(" ready=").append(h.ready).append(" act=").append(h.curAction == null ? "-" : h.curAction.getClass().getSimpleName())
							.append(" waiting=").append(com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime.isWaiting(h))
							.append(" t=").append(Actor.coopTime(h));
					sb.append(" processing=").append(Actor.processing()).append(" inputReady=").append(CoopRemote.inputReady())
							.append(" dungeonHero=P").append(Coop.indexOf(Dungeon.hero)+1).append(" actions=").append(actions)
							.append(" lastUse=").append(CoopRemote.lastUseResult);
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
	static boolean gaveScroll = false;

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
				client.pick( "MAGE" );
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
					com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff st = staff();
					int after = st == null ? -1 : st.level();
					log( "items: " + zaps + " zaps, " + cellAnswers + " cell prompts answered, " + itemAnswers
							+ " item prompts answered, staff level " + staffLevelBefore + " -> " + after );
					if (zaps > 0 && cellAnswers == 0) CoopSim.problem( "zapped " + zaps + " times but never got a cell prompt" );
					if (!scrollRead) CoopSim.problem( "the client's hero never got the scroll of upgrade" );
					else if (itemAnswers == 0) CoopSim.problem( "reading the scroll of upgrade gave no item prompt" );
					else if (after <= staffLevelBefore) CoopSim.problem( "the staff was not upgraded (level " + staffLevelBefore + " -> " + after + ")" );
					CoopSim.finish();
					return;
				}
				if (!(Game.scene() instanceof GameScene) || !CoopRemote.onlineClient() || Dungeon.hero == null) return;
				if (Dungeon.depth != lastDepth){
					lastDepth = Dungeon.depth;
					lastProgress = System.currentTimeMillis();
					log( "mirror on depth " + Dungeon.depth + " as " + Dungeon.hero.heroClass + " (party index " + CoopRemote.localPartyIndex() + ")" );
					if (Dungeon.hero.heroClass != HeroClass.MAGE) CoopSim.problem( "client controls a " + Dungeon.hero.heroClass + ", expected its Mage" );
				}
				if (answerPrompts()) return;
				if (!CoopRemote.inputReady()) return;
				if (frames % 3 != 0) return;
				if (useItems()) return;
				int cell = chooseCell( Dungeon.hero );
				if (cell != -1){
					CoopRemote.onCellSelected( cell );
					actions++;
				}
		}
	}

	// ---------------------------------------------------------------------------------
	// Client items (M4): zapping goes through a cell prompt, upgrading through an item prompt
	// ---------------------------------------------------------------------------------

	static int zaps = 0, cellAnswers = 0, itemAnswers = 0;
	static boolean scrollRead = false;
	static int staffLevelBefore = -1;
	static int lastAnswered = -1;

	static com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff staff(){
		for (com.shatteredpixel.shatteredpixeldungeon.items.Item i : com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopItems.all( Dungeon.hero )){
			if (i instanceof com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff) return (com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff) i;
		}
		return null;
	}

	static boolean useItems(){
		Hero me = Dungeon.hero;
		if (!scrollRead && actions > 15){
			for (com.shatteredpixel.shatteredpixeldungeon.items.Item i : com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopItems.all( me )){
				if (i instanceof com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfUpgrade){
					scrollRead = true;
					com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff st = staff();
					staffLevelBefore = st == null ? -1 : st.level();
					log( "reading the scroll of upgrade at " + com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopItems.pathOf( me, i ) + " (staff level " + staffLevelBefore + ")" );
					CoopRemote.useItem( i, me, com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll.AC_READ );
					actions++;
					return true;
				}
			}
		}
		com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff st = staff();
		if (st != null && actions % 7 == 0){
			for (Mob m : Dungeon.level.mobs){
				if (m.alignment == Char.Alignment.ENEMY && me.fieldOfView != null && me.fieldOfView[m.pos]
						&& Dungeon.level.distance( me.pos, m.pos ) <= 5){
					zapTarget = m.pos;
					zaps++;
					CoopRemote.useItem( st, me, com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff.AC_ZAP );
					actions++;
					return true;
				}
			}
		}
		return false;
	}

	static int zapTarget = -1;

	//answers the host's prompt like a player would
	static boolean answerPrompts(){
		int id = CoopRemote.lastPromptId;
		if (id < 0 || id == lastAnswered) return false;
		lastAnswered = id;
		String type = CoopRemote.lastPromptType;
		if (CoopRemote.PROMPT_CELL.equals( type )){
			cellAnswers++;
			log( "cell prompt " + id + ": aiming at " + zapTarget );
			GameScene.cancelCellSelector();
			CoopRemote.answerCell( id, zapTarget );
		} else if (CoopRemote.PROMPT_ITEM.equals( type )){
			itemAnswers++;
			String[] paths = CoopRemote.lastPromptPaths;
			String pick = null;
			com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff st = staff();
			String staffPath = com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopItems.pathOf( Dungeon.hero, st );
			for (String path : paths) if (path.equals( staffPath )) pick = path;
			if (pick == null && paths.length > 0) pick = paths[0];
			log( "item prompt " + id + ": " + paths.length + " choices, picking " + pick + " (staff is " + staffPath + ")" );
			GameScene.cancel();
			CoopRemote.answerItem( id, pick );
		} else if (CoopRemote.PROMPT_OPT.equals( type )){
			log( "option prompt " + id + ": choosing 0" );
			GameScene.cancel();
			CoopRemote.answerOption( id, 0 );
		}
		return true;
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
