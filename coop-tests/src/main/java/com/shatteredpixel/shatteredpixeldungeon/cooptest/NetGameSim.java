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
					+ " depth " + Dungeon.depth + (Dungeon.hero != null ? " pos " + Dungeon.hero.pos : "") + (isHost ? "" : " | " + CoopRemote.clientDebug() + " | replay error: " + CoopRemote.lastReplayError) + " | " + CoopSim.Driver.actorDump() );
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
				com.shatteredpixel.shatteredpixeldungeon.utils.GLog.update.add( text -> { log( "glog: " + text ); return false; } );
				lastProgress = System.currentTimeMillis();
				phase = 2;
				return;
			case 2:
				if (!(Game.scene() instanceof GameScene) || Dungeon.hero == null || !CoopRemote.onlineHost()) return;
				if (Dungeon.depth != lastDepth){
					lastDepth = Dungeon.depth;
					depthFrame = frames;
					lastProgress = System.currentTimeMillis();
					log( "now on depth " + Dungeon.depth );
				}
				if (Dungeon.depth >= targetDepth){
					log( "reached depth " + Dungeon.depth + " after " + actions + " actions, closing the session; host clicks " + safeClicks + " (" + unsafeClicks + " outside a safe point), bolts sent " + CoopRemote.missilesSent );
					if (safeClicks == 0) CoopSim.problem( "the host's clicks never ran" );
					if (unsafeClicks > 0) CoopSim.problem( unsafeClicks + " host clicks ran while the game logic was running" );
					CoopOnline.leave();
					CoopSim.finish();
					return;
				}
				cheats();
				if (Coop.size() > 1 && !Actor.processing()) hostScenario( Coop.get(1) );
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

	//what the host sets up for the client's hero (render thread, the game isn't running):
	// - at the start: a scroll of upgrade, three seeds, gold, and enough levels for talent points
	// - on depth 2: a shopkeeper and an item for sale next to it
	// - on depth 3: the alchemy screen, as if the hero had reached a pot
	static boolean gaveItems = false, shopPlaced = false, alchemyOpened = false;
	static String lastAlchemyLogged = "";
	static int depthFrame = 0;
	static boolean gasPlaced = false;
	static int lastClickAt = 0, safeClicks = 0, unsafeClicks = 0;

	static void hostScenario( Hero h ){
		if (!gaveItems){
			gaveItems = true;
			h.belongings.backpack.items.add( new com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfUpgrade().identify() );
			h.belongings.backpack.items.add( new com.shatteredpixel.shatteredpixeldungeon.plants.Sungrass.Seed().quantity( 3 ) );
			h.belongings.backpack.items.add( new com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfMetamorphosis().identify() );
			h.lvl = 3;
			Dungeon.gold = 1000;
			log( "gave the client's hero a scroll of upgrade, 3 seeds, level 3 and the party 1000 gold" );
		}
		if (!gasPlaced && Dungeon.depth == 1 && frames > depthFrame + 300){
			gasPlaced = true;
			int cell = h.pos + Dungeon.level.width();
			if (cell >= Dungeon.level.length() || Dungeon.level.solid[cell]) cell = h.pos;
			GameScene.add( com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob.seed( cell, 300, com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ToxicGas.class ) );
			log( "released toxic gas at " + cell + " next to the client's hero" );
		}
		//the host player's own button clicks must run while the game logic is parked
		if (actions > lastClickAt + 40){
			lastClickAt = actions;
			com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopRemote.uiClick( () -> {
				safeClicks++;
				if (!com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopRemote.atSafePoint()) unsafeClicks++;
			});
		}
		if (!shopPlaced && Dungeon.depth == 2 && frames > depthFrame + 200 && com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime.isWaiting( h )){
			Level level = Dungeon.level;
			int keeper = -1, ware = -1;
			for (int n : PathFinder.NEIGHBOURS8){
				int c = h.pos + n;
				if (c < 0 || c >= level.length() || !level.passable[c] || level.pit[c] || Actor.findChar( c ) != null || level.heaps.get( c ) != null) continue;
				if (keeper == -1){
					keeper = c;
				} else {
					ware = c;
					break;
				}
			}
			if (keeper == -1 || ware == -1) return;
			shopPlaced = true;
			com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Shopkeeper sk = new com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Shopkeeper();
			sk.pos = keeper;
			GameScene.add( sk );
			com.shatteredpixel.shatteredpixeldungeon.items.Heap heap = level.drop( new com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing().identify(), ware );
			heap.type = com.shatteredpixel.shatteredpixeldungeon.items.Heap.Type.FOR_SALE;
			log( "placed a shopkeeper at " + keeper + " and a potion for sale at " + ware + " next to the client's hero at " + h.pos );
		}
		//(a while after arriving: the new floor's snapshot goes out first)
		if (!alchemyOpened && Dungeon.depth == 3 && frames > depthFrame + 200 && com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime.isWaiting( h )){
			alchemyOpened = true;
			boolean sent = com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopCommands.openAlchemy( h, null );
			log( "opened the alchemy screen for the client's hero: " + sent );
		}
		String r = com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopCommands.lastAlchemyResult;
		if (!r.equals( lastAlchemyLogged )){
			lastAlchemyLogged = r;
			log( "alchemy: " + r );
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
					checkScenario();
					CoopSim.finish();
					return;
				}
				if (CoopRemote.checksumMismatches != seenMismatches || CoopRemote.reconnects != seenReconnects){
					seenMismatches = CoopRemote.checksumMismatches;
					seenReconnects = CoopRemote.reconnects;
					log( "frame " + frames + ": mismatches " + seenMismatches + ", reconnects " + seenReconnects + ", scene "
							+ Game.scene().getClass().getSimpleName() + ", depth " + Dungeon.depth + ", updates " + CoopRemote.updatesApplied );
				}
				if (!sawGas && Dungeon.level != null){
					com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob gas = Dungeon.level.blobs.get( com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ToxicGas.class );
					if (gas != null && gas.volume > 0){
						sawGas = true;
						log( "the mirror shows toxic gas (volume " + gas.volume + ")" );
					}
				}
				if (Game.scene() instanceof com.shatteredpixel.shatteredpixeldungeon.scenes.AlchemyScene){
					doAlchemy();
					return;
				}
				if (!(Game.scene() instanceof GameScene) || !CoopRemote.onlineClient() || Dungeon.hero == null) return;
				if (Dungeon.depth != lastDepth){
					lastDepth = Dungeon.depth;
					lastProgress = System.currentTimeMillis();
					log( "mirror on depth " + Dungeon.depth + " as " + Dungeon.hero.heroClass + " (party index " + CoopRemote.localPartyIndex() + ")" );
					if (Dungeon.hero.heroClass != HeroClass.MAGE) CoopSim.problem( "client controls a " + Dungeon.hero.heroClass + ", expected its Mage" );
				}
				if (frames % 1500 == 0) log( "frame " + frames + " actions " + actions + " shop " + shopStage + " | " + CoopRemote.clientDebug() );
				if (answerPrompts()) return;
				if (!CoopRemote.inputReady()) return;
				if (frames % 3 != 0) return;
				if (scenario()) return;
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
		if (scrollRead && !metaRead && actions > 30 && (shopStage == 0 || shopStage == 5)){
			for (com.shatteredpixel.shatteredpixeldungeon.items.Item i : com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopItems.all( me )){
				if (i instanceof com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfMetamorphosis){
					metaRead = true;
					log( "reading the scroll of metamorphosis" );
					CoopRemote.useItem( i, me, com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll.AC_READ );
					actions++;
					return true;
				}
			}
		}
		com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff st = staff();
		if (st != null && actions % 7 == 0 && actions != lastZapAt){
			lastZapAt = actions;
			for (Mob m : Dungeon.level.mobs){
				if (m.alignment == Char.Alignment.ENEMY && me.fieldOfView != null && me.fieldOfView[m.pos]
						&& Dungeon.level.distance( me.pos, m.pos ) <= 5
						&& staffCharges( st ) > 0){
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

	//the staff's wand charges (the wand is private to the staff)
	static int staffCharges( com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff st ){
		try {
			java.lang.reflect.Field f = com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff.class.getDeclaredField( "wand" );
			f.setAccessible( true );
			com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand w = (com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand) f.get( st );
			return w == null ? 0 : w.curCharges;
		} catch (Exception e){
			return 0;
		}
	}
	static boolean metaRead = false, sawGas = false;
	static int lastZapAt = -1;

	//answers the host's prompt like a player would
	static boolean answerPrompts(){
		int id = CoopRemote.lastPromptId;
		if (id < 0 || id == lastAnswered) return false;
		lastAnswered = id;
		String type = CoopRemote.lastPromptType;
		if (CoopRemote.PROMPT_CELL.equals( type )){
			cellAnswers++;
			log( "cell prompt " + id + ": aiming at " + zapTarget );
			//answer first: closing the selector answers "cancelled", which the host then ignores
			CoopRemote.answerCell( id, zapTarget );
			GameScene.cancelCellSelector();
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
			int pick = 0;
			String note = "";
			if (shopStage <= 1 && CoopRemote.lastPromptTitle.contains( "Shopkeeper" )){
				//(also when the bot bumped into the shopkeeper on its way)
				if (shopStage == 0) goldBefore = Dungeon.gold;
				pick = 1; //talk
				shopStage = 2;
				note = " (shop menu)";
			} else if (shopStage == 2){
				shopStage = 3;
				note = " (shopkeeper's words)";
			} else if (shopStage == 4){
				shopStage = 5;
				shopDoneFrame = frames;
				goldAfterBuy = -1;
				note = " (trade window)";
			}
			log( "option prompt " + id + " '" + CoopRemote.lastPromptTitle + "' " + java.util.Arrays.toString( CoopRemote.lastPromptOptions ) + ": choosing " + pick + note );
			GameScene.cancel();
			CoopRemote.answerOption( id, pick );
		}
		return true;
	}

	// ---------------------------------------------------------------------------------
	// Client scenario (M5): talents, a shop (deferred windows, a window without buttons, a
	// trade window), alchemy, and a dropped connection
	// ---------------------------------------------------------------------------------

	static int seenMismatches = 0, seenReconnects = 0;
	static int shopStage = 0; //1 asked the shopkeeper, 2 chose talk, 3 heard them, 4 asked to buy, 5 bought
	static int goldBefore = -1, shopDoneFrame = 0, shopAskFrame = 0, goldAfterBuy = -1;
	static boolean talentDone = false;
	static String talentName = null;
	static int talentBefore = -1;
	static boolean dropped = false;
	static boolean alchemyDone = false;
	static int alchemyFrame = 0, potionsBefore = -1, seedsBefore = 0;

	static boolean scenario(){
		Hero me = Dungeon.hero;
		Level level = Dungeon.level;
		if (!talentDone && me.talentPointsAvailable( 1 ) > 0){
			talentDone = true;
			com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent t = me.talents.get( 0 ).keySet().iterator().next();
			talentName = t.name();
			talentBefore = me.talentPointsSpent( 1 );
			log( "spending a talent point on " + talentName + " (" + talentBefore + " points)" );
			com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopCommands.upgradeTalent( me, t );
			return true;
		}
		if (shopStage == 0){
			for (Mob m : level.mobs){
				if (m instanceof com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Shopkeeper){
					shopStage = 1;
					shopAskFrame = frames;
					goldBefore = Dungeon.gold;
					log( "talking to the shopkeeper at " + m.pos + " (gold " + goldBefore + ")" );
					CoopRemote.onCellSelected( m.pos );
					return true;
				}
			}
		}
		if (shopStage == 3){
			for (com.shatteredpixel.shatteredpixeldungeon.items.Heap h : level.heaps.valueList()){
				if (h.type == com.shatteredpixel.shatteredpixeldungeon.items.Heap.Type.FOR_SALE){
					shopStage = 4;
					shopAskFrame = frames;
					log( "buying " + h.peek().getClass().getSimpleName() + " at " + h.pos );
					CoopRemote.onCellSelected( h.pos );
					return true;
				}
			}
		}
		//a tap the host dropped (the hero wasn't waiting there yet): tap again, like a player would
		if ((shopStage == 1 || shopStage == 4) && frames - shopAskFrame > 300){
			shopAskFrame = frames;
			for (Mob m : level.mobs){
				if (shopStage == 1 && m instanceof com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Shopkeeper){
					log( "no answer from the shopkeeper yet, tapping again" );
					CoopRemote.onCellSelected( m.pos );
				}
			}
			for (com.shatteredpixel.shatteredpixeldungeon.items.Heap h : level.heaps.valueList()){
				if (shopStage == 4 && h.type == com.shatteredpixel.shatteredpixeldungeon.items.Heap.Type.FOR_SALE){
					log( "no trade window yet, tapping the item again" );
					CoopRemote.onCellSelected( h.pos );
				}
			}
			return true;
		}
		//the gold a moment after buying (the host's update has come by then)
		if (shopStage == 5 && goldAfterBuy < 0 && frames > shopDoneFrame + 60){
			goldAfterBuy = Dungeon.gold;
			log( "gold after buying: " + goldBefore + " -> " + goldAfterBuy );
		}
		//stay with the shopkeeper until the shopping is done
		if (shopStage >= 1 && shopStage < 5) return true;
		if (!dropped && Dungeon.depth >= 2 && (shopStage == 5 && frames > shopDoneFrame + 100 || Dungeon.depth >= 3)){
			dropped = true;
			log( "dropping the connection" );
			CoopOnline.client.dropConnection();
			return true;
		}
		return false;
	}

	static int count( Class<?> cls ){
		int n = 0;
		for (com.shatteredpixel.shatteredpixeldungeon.items.Item i : com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopItems.all( Dungeon.hero )){
			if (cls.isInstance( i )) n += i.quantity();
		}
		return n;
	}

	//the host opened the alchemy screen: brew the three seeds into a potion, then go back
	static void doAlchemy(){
		if (alchemyDone){
			if (frames - alchemyFrame > 30){
				log( "leaving the alchemy screen" );
				com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon.switchScene( GameScene.class );
				alchemyFrame = Integer.MAX_VALUE / 2;
			}
			return;
		}
		alchemyDone = true;
		alchemyFrame = frames;
		potionsBefore = count( com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion.class );
		seedsBefore = count( com.shatteredpixel.shatteredpixeldungeon.plants.Sungrass.Seed.class );
		com.shatteredpixel.shatteredpixeldungeon.items.Item seeds = null;
		for (com.shatteredpixel.shatteredpixeldungeon.items.Item i : com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopItems.all( Dungeon.hero )){
			if (i instanceof com.shatteredpixel.shatteredpixeldungeon.plants.Sungrass.Seed) seeds = i;
		}
		if (seeds == null || seeds.quantity() < 3){
			CoopSim.problem( "alchemy screen opened but the mirror has no three seeds" );
			return;
		}
		java.util.ArrayList<com.shatteredpixel.shatteredpixeldungeon.items.Item> ingredients = new java.util.ArrayList<>();
		for (int k = 0; k < 3; k++) ingredients.add( seeds.duplicate().quantity( 1 ) );
		log( "in the alchemy screen: brewing 3 seeds (" + potionsBefore + " potions now)" );
		com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopCommands.alchemyCombine( ingredients, 0 );
	}

	static void checkScenario(){
		Hero me = Dungeon.hero;
		log( "scenario: shop stage " + shopStage + ", gold " + goldBefore + " -> " + Dungeon.gold
				+ ", healing potions " + count( com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing.class )
				+ ", reconnects " + CoopRemote.reconnects + ", seeds " + count( com.shatteredpixel.shatteredpixeldungeon.plants.Sungrass.Seed.class )
				+ ", potions " + potionsBefore + " -> " + count( com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion.class ) );
		if (!talentDone){
			CoopSim.problem( "the client's hero never had a talent point" );
		} else {
			int now = me.talentPointsSpent( 1 );
			log( "tier 1 talent points spent: " + talentBefore + " -> " + now + ", metamorphosed " + me.metamorphedTalents );
			if (now != talentBefore + 1) CoopSim.problem( "tier 1 talent points went " + talentBefore + " -> " + now + " on the host's hero" );
		}
		if (shopStage < 5) CoopSim.problem( "shopping stopped at stage " + shopStage );
		else {
			if (goldAfterBuy < 0 || goldAfterBuy >= goldBefore) CoopSim.problem( "buying cost nothing (gold " + goldBefore + " -> " + goldAfterBuy + ")" );
			if (count( com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing.class ) == 0) CoopSim.problem( "the bought potion isn't in the hero's bag" );
		}
		if (!alchemyDone) CoopSim.problem( "the alchemy screen never opened on the client" );
		else {
			if (count( com.shatteredpixel.shatteredpixeldungeon.plants.Sungrass.Seed.class ) >= seedsBefore) CoopSim.problem( "the seeds weren't used up by alchemy" );
			if (count( com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion.class ) <= potionsBefore) CoopSim.problem( "alchemy gave no potion" );
		}
		if (!metaRead) CoopSim.problem( "the client never read the scroll of metamorphosis" );
		else if (me.metamorphedTalents.isEmpty()) CoopSim.problem( "metamorphosis changed no talent" );
		log( "effects: gas seen " + sawGas + " (" + CoopRemote.blobsApplied + " gas updates), " + CoopRemote.missilesReplayed + " missiles/bolts drawn; last replay error: " + CoopRemote.lastReplayError );
		if (!sawGas) CoopSim.problem( "the mirror never showed the host's toxic gas" );
		if (zaps > 0 && CoopRemote.missilesReplayed == 0) CoopSim.problem( "zapped " + zaps + " times but no bolt was drawn" );
		if (!dropped) CoopSim.problem( "the connection was never dropped" );
		else if (CoopRemote.reconnects == 0) CoopSim.problem( "the client never got back in after dropping" );
	}

	// ---------------------------------------------------------------------------------
	// Bot: fight what's next to you, otherwise step towards the stairs (one tile at a time,
	// so it works on the client's mirror too)
	// ---------------------------------------------------------------------------------

	static int lastPos = -1;
	static int stuck = 0;

	static int chooseCell( Hero me ){
		int c = chooseCell0( me );
		//tapping yourself on the stairs you came down would take the party back up: wait instead
		LevelTransition here = Dungeon.level.getTransition( me.pos );
		if (c == me.pos && here != null && here.type != LevelTransition.Type.REGULAR_EXIT){
			me.rest( false );
			actions++;
			return -1;
		}
		return c;
	}

	static int chooseCell0( Hero me ){
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
