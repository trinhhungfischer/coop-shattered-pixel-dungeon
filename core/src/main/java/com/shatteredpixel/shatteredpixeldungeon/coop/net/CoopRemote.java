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

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndMessage;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;
import com.watabou.utils.FileUtils;
import com.watabou.utils.Reflection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The game side of online coop (docs/coop-online-design.md, M3). The host runs the real game;
 * clients show a mirror of it and send their player's commands.
 *
 * host -> client
 *  snap {seq, game, level}  the host's save files (base64): the client loads them like a saved game
 *  upd  {seq, ...}          about 10 times a second: every char's id/pos/HP/HT/buffs, chars new to
 *                           the client (full bundles), the map when it changed, heaps when they
 *                           changed, hero bundles when they changed, which heroes wait for input,
 *                           animation/status/log events, and a checksum of the char state
 * client -> host
 *  cell {cell}              the player tapped a cell (like a solo player tapping the map)
 *  act {kind}               wait, rest or search from the toolbar
 *  use {path, cls, action}  use an item (see CoopItems for paths)
 *  answer {id, cell|path|index}  the answer to a prompt (cell -1 / no path / index -1: cancelled)
 * host -> client (prompts while executing that client's command)
 *  pcell {id, text}         choose a cell (wand zaps, throwing, targeted spells...)
 *  pitem {id, text, paths}  choose one of these items (upgrade, identify...)
 *  popt {id, title, text, options, enabled}  choose an option (confirmations...)
 *
 * The host executes every command (its own player's too) on its render thread while the actor
 * thread is parked at a safe point, exactly like a solo player's UI input.
 *  resync                   the client is missing something: send a new snapshot
 *
 * Threads: client messages are queued and applied by the host's actor thread at the top of each
 * scheduling pass (a safe point, nothing else is acting); host messages are queued on the client
 * and applied on its render thread. The client never runs game logic: no actor thread, and
 * animation callbacks don't call back into the game.
 */
public class CoopRemote {

	public static final String SNAP   = "snap";
	public static final String UPD    = "upd";
	public static final String CELL   = "cell";
	public static final String RESYNC = "resync";
	public static final String ACT    = "act";
	public static final String USE    = "use";
	//prompts the host forwards to the client whose hero needs to choose, and the answers
	public static final String PROMPT_CELL = "pcell";
	public static final String PROMPT_ITEM = "pitem";
	public static final String PROMPT_OPT  = "popt";
	public static final String ANSWER      = "answer";

	//kinds of command besides tapping a cell (wait, rest, search from the toolbar)
	public static final int ACT_CELL      = 0;
	public static final int ACT_REST      = 1;
	public static final int ACT_REST_FULL = 2;
	public static final int ACT_SEARCH    = 3;

	//save slot the client keeps the host's game in
	public static final int CLIENT_SLOT = 90;

	private static final long UPDATE_MS = 100;
	private static final long HERO_BUNDLE_MS = 400;

	//host: the player slot of each party index
	public static int[] slotsByPartyIndex = new int[0];

	public static boolean onlineHost(){
		return CoopOnline.isHost() && hostInGame;
	}

	public static boolean onlineClient(){
		return CoopOnline.isClient() && clientInGame;
	}

	public static boolean online(){
		return onlineHost() || onlineClient();
	}

	//the hero this machine's player controls
	public static Hero localHero(){
		if (!online()) return Dungeon.hero;
		int i = localPartyIndex();
		return i >= 0 && i < Coop.size() ? Coop.get( i ) : Dungeon.hero;
	}

	public static int localPartyIndex(){
		if (onlineHost()) return 0;
		return partyIndexOfSlot( CoopOnline.localSlot );
	}

	private static int partyIndexOfSlot( int slot ){
		for (int i = 0; i < slotsByPartyIndex.length; i++){
			if (slotsByPartyIndex[i] == slot) return i;
		}
		return -1;
	}

	// ---------------------------------------------------------------------------------
	// Shared hooks
	// ---------------------------------------------------------------------------------

	//GameScene's default cell listener: online, a tap becomes a command. Returns true if handled.
	public static boolean onCellSelected( Integer cell ){
		if (cell == null) return false;
		if (onlineClient()){
			Bundle m = NetConnection.message( CELL );
			m.put( "cell", cell );
			sendCommand( m );
			Hero me = localHero();
			if (me != null) me.ready = false; //until the host says we wait again
			return true;
		}
		if (onlineHost()){
			if (applyingCommand) return false;
			final int c = cell;
			hostTask( 0, () -> { Hero h = Coop.get(0); if (CoopTime.isWaiting( h )) h.handle( c ); } );
			return true;
		}
		return false;
	}

	private static volatile boolean applyingCommand = false;

	//true while the host runs a command (the UI calls it made run directly)
	static boolean applyingCommand(){
		return applyingCommand;
	}

	//Hero.rest / Hero.search from the toolbar: online they become commands. Returns true if forwarded.
	public static boolean forwardAction( Hero hero, int kind ){
		if (applyingCommand || hero != localHero()) return false;
		if (onlineClient()){
			Bundle m = NetConnection.message( ACT );
			m.put( "kind", kind );
			sendCommand( m );
			hero.ready = false;
			return true;
		}
		if (onlineHost()){
			hostTask( 0, () -> act( Coop.get(0), kind ) );
			return true;
		}
		return false;
	}

	private static void act( Hero h, int kind ){
		if (!CoopTime.isWaiting( h )) return;
		switch (kind){
			case ACT_REST:      h.rest( false ); break;
			case ACT_REST_FULL: h.rest( true ); break;
			case ACT_SEARCH:    h.search( true ); break;
		}
	}

	//UI item use (inventory, quickslots, item windows): online it becomes a command
	public static void useItem( Item item, Hero hero ){
		useItem( item, hero, item.defaultAction() );
	}

	public static void useItem( Item item, Hero hero, String action ){
		if (applyingCommand || !online() || action == null){
			item.execute( hero, action );
			return;
		}
		Hero me = localHero();
		String path = CoopItems.pathOf( me, item );
		if (onlineClient()){
			if (path == null) return;
			Bundle m = NetConnection.message( USE );
			m.put( "path", path );
			m.put( "cls", item.getClass().getName() );
			m.put( "action", action );
			sendCommand( m );
			//until the host says we wait again (no burst of uses in one turn)
			me.ready = false;
			return;
		}
		//host: its own item, at the next safe point, on its turn
		hostTask( 0, () -> { Hero h = Coop.get(0); if (CoopTime.isWaiting( h )) item.execute( h, action ); } );
	}

	//ui.Button clicks: online, the host player's clicks in the game run at a safe point, like their
	//taps on the map, so a window button can't change the game while another hero's turn runs
	public static void uiClick( Runnable click ){
		if (!onlineHost() || applyingCommand || !(Game.scene() instanceof GameScene)){
			click.run();
			return;
		}
		Hero me = localHero();
		if (me == null || !me.isAlive() || Coop.isDead( me )){
			//a spectating host has no turns to wait for
			click.run();
			return;
		}
		hostTask( 0, click );
	}

	//GameScene.update: whether the player may give input now
	public static boolean inputReady(){
		if (onlineHost()){
			Hero me = localHero();
			return me != null && me.isAlive() && !Coop.isDead( me ) && CoopTime.isWaiting( me );
		}
		if (onlineClient()){
			Hero me = localHero();
			return me != null && me.ready && me.isAlive();
		}
		return Dungeon.hero.ready;
	}

	// ---------------------------------------------------------------------------------
	// Host
	// ---------------------------------------------------------------------------------

	private static volatile boolean hostInGame = false;
	private static final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();

	private static volatile boolean snapshotNeeded = true;
	private static Level sentLevel = null;
	//snapshot number: commands a client made for an older snapshot (another level) are dropped
	private static volatile int seq = 0;
	private static long lastUpdate = 0;
	private static final HashSet<Integer> sentIds = new HashSet<>();
	private static int mapHash = 0;
	private static int heapHash = 0;
	private static int[] heroHash = new int[0];
	private static long lastHeroBundles = 0;

	public static void startHost(){
		hostInGame = true;
		snapshotNeeded = true;
		sentLevel = null;
		tasks.clear();
		events.clear();
		CoopTime.online = true;
		CoopCommands.clear();
		Game.callbackWrapper = deferredWrapper;
		CoopOnline.host.setEvents( new CoopHost.Events() {
			@Override
			public void onGameMessage( int slot, Bundle message ) {
				int idx = partyIndexOfSlot( slot );
				if (idx < 0) return;
				String type = NetConnection.type( message );
				//made on another level (the host changed floors before the client heard of it)
				if (!type.equals( RESYNC ) && message.contains( "seq" ) && message.getInt( "seq" ) != seq){
					droppedStale++;
					return;
				}
				if (type.equals( CELL ) || type.equals( ACT ) || type.equals( USE )){
					hostTask( idx, () -> CoopCommands.closeAlchemy( Coop.get( idx ) ) );
				}
				switch (type){
					case CoopCommands.TALENT:
						hostTask( idx, () -> CoopCommands.onTalent( Coop.get( idx ), message ) );
						break;
					case CoopCommands.ALCHEMY:
						hostTask( idx, () -> CoopCommands.onAlchemy( Coop.get( idx ), message ) );
						break;
					case CoopCommands.ENERGIZE:
						hostTask( idx, () -> CoopCommands.onEnergize( Coop.get( idx ), message ) );
						break;
					case CELL:
						final int cell = message.getInt( "cell" );
						hostTask( idx, () -> {
							Hero h = Coop.get( idx );
							if (CoopTime.isWaiting( h )) h.handle( cell ); else droppedNotWaiting++;
						});
						break;
					case ACT:
						final int kind = message.getInt( "kind" );
						hostTask( idx, () -> act( Coop.get( idx ), kind ) );
						break;
					case USE:
						final String path = message.getString( "path" ), cls = message.getString( "cls" ), action = message.getString( "action" );
						hostTask( idx, () -> {
							Hero h = Coop.get( idx );
							//like any other action, only on the hero's turn
							if (!CoopTime.isWaiting( h )) return;
							Item it = CoopItems.at( h, path );
							if (it == null || !it.getClass().getName().equals( cls )){
								lastUseResult = "no item " + cls + " at " + path + (it == null ? "" : " (found " + it.getClass().getSimpleName() + ")");
								return;
							}
							if (!it.actions( h ).contains( action )){
								lastUseResult = it.getClass().getSimpleName() + " can't " + action + " " + it.actions( h );
								return;
							}
							lastUseResult = "used " + it.getClass().getSimpleName() + " " + action;
							it.execute( h, action );
						});
						break;
					case ANSWER:
						final Bundle answer = message;
						hostTask( idx, () -> {
							answerPrompt( Coop.get( idx ), answer );
							resendOpenWindow( Coop.get( idx ) );
						});
						break;
					case RESYNC:
						snapshotNeeded = true;
						break;
				}
			}

			@Override
			public void onPlayerLeft( int slot, String name, String reason ) {
				events.add( "log|" + Messages.get( CoopRemote.class, "player_left", name ) );
			}

			@Override
			public void onPlayerDropped( int slot, String name, String reason ) {
				events.add( "log|" + Messages.get( CoopRemote.class, "player_dropped", name ) );
			}

			@Override
			public void onPlayerRejoined( int slot, String name ) {
				events.add( "log|" + Messages.get( CoopRemote.class, "player_back", name ) );
				rejoined.add( slot );
			}
		});
	}

	//players who came back: they get a snapshot of the current state at the next safe point
	private static final ConcurrentLinkedQueue<Integer> rejoined = new ConcurrentLinkedQueue<>();
	//heroes of dropped players already given a wait this pass
	private static final HashSet<Integer> autoWaiting = new HashSet<>();

	//a dropped player's hero must not hold up a fight: while it shares a turn group with someone,
	//it waits a turn whenever it is its turn
	private static void waitForDropped(){
		for (int i = 1; i < Coop.size() && i < slotsByPartyIndex.length; i++){
			Hero h = Coop.get( i );
			if (CoopOnline.host.isConnected( slotsByPartyIndex[i] ) || !h.isAlive() || Coop.isDead( h )) continue;
			if (!CoopTime.isWaiting( h ) || autoWaiting.contains( i )) continue;
			boolean linked = false;
			for (Hero o : Coop.aliveHeroes()){
				if (o != h && !Coop.isDead( o ) && CoopTime.sameGroup( h, o )) linked = true;
			}
			if (!linked) continue;
			final int idx = i;
			autoWaiting.add( idx );
			hostTask( idx, () -> {
				autoWaiting.remove( idx );
				act( Coop.get( idx ), ACT_REST );
			});
		}
	}

	//Actor.process, at the top of every scheduling pass (actor thread, nothing is acting)
	public static void beforeScan(){
		if (!onlineHost() || Dungeon.level == null || Dungeon.hero == null) return;

		//nothing acts here: the host's screen goes back to its own hero (e.g. after a teammate's
		//resurrection left the pointer on them)
		Hero me = localHero();
		if (me != null && me.isAlive() && !Coop.isDead( me ) && Dungeon.hero != me && heroAwaitingResurrection() == null){
			Coop.setActive( me );
		}

		waitForDropped();
		parkForTasks();
		if (Game.switchingScene()){
			waitForSceneSwitch();
			return;
		}

		if (Dungeon.level != sentLevel) snapshotNeeded = true;
		long now = System.currentTimeMillis();
		if (snapshotNeeded){
			sendSnapshot();
			rejoined.clear();
		} else {
			Integer slot;
			while ((slot = rejoined.poll()) != null){
				//same seq: the others' updates stay valid, and the returning client takes them from here
				Bundle snap = snapshot( seq );
				if (snap != null) CoopOnline.host.send( slot, snap );
			}
			if (now - lastUpdate >= UPDATE_MS) sendUpdate();
		}
	}

	// --- commands run as tasks on the host's render thread, with the actor thread parked ---

	private static final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
	private static final Object park = new Object();
	private static volatile boolean parked = false;

	//the hero whose command is running: prompts for a remote hero go to that hero's player
	private static volatile Hero promptHero = null;

	static void hostTask( int partyIndex, Runnable r ){
		hostTask( partyIndex, r, false );
	}

	//evenIfDead: the task still runs (without its hero's prompts) once that hero has died
	private static void hostTask( int partyIndex, Runnable r, boolean evenIfDead ){
		tasks.add( () -> {
			if (partyIndex < 0 || partyIndex >= Coop.size()) return;
			Hero h = Coop.get( partyIndex );
			//a hero that died holding an ankh isn't dead yet: its player chooses in WndResurrect
			if (Coop.isDead( h ) || (!h.isAlive() && !resurrectPending())){
				if (evenIfDead) r.run();
				return;
			}
			Hero before = promptHero;
			promptHero = h;
			applyingCommand = true;
			Coop.setActive( h );
			try {
				r.run();
			} finally {
				applyingCommand = false;
				promptHero = before;
				Hero me = localHero();
				//an ankh's resurrection about to run works on Dungeon.hero: keep pointing at the
				//hero coming back (beforeScan points back at ours afterwards)
				boolean resurrecting = InterlevelScene.mode == InterlevelScene.Mode.RESURRECT && Game.switchingScene();
				Hero pending = heroAwaitingResurrection();
				if (resurrecting){
					//stays
				} else if (pending != null){
					//while a player chooses in WndResurrect the game is paused like in solo, where
					//Dungeon.hero is the fallen hero: GameScene only wakes the game logic for a living
					//Dungeon.hero, so pointing back at ours would start turns now
					Coop.setActive( pending );
				} else if (me != null && me.isAlive()){
					Coop.setActive( me );
				}
			}
		});
	}

	//the hero that fell holding an ankh while its player chooses in WndResurrect (null if none)
	private static Hero heroAwaitingResurrection(){
		if (!resurrectPending()) return null;
		for (Hero h : Coop.heroes()){
			if (!h.isAlive() && !Coop.isDead( h )) return h;
		}
		return null;
	}

	//Game.runOnRenderThread: NPCs, shops and many items show their windows a frame later. A
	//callback made for a remote hero (while it acts, or while its command runs) runs as that
	//hero's task, so its windows go to its player and its choices run at a safe point.
	private static Game.CallbackWrapper deferredWrapper = c -> {
		if (!onlineHost()) return c;
		Hero owner = promptHero;
		if (owner == null && Thread.currentThread().getName().equals( "SHPD Actor Thread" )) owner = Dungeon.hero;
		if (owner == null || owner == localHero()) return c;
		final int idx = Coop.indexOf( owner );
		if (idx <= 0) return c;
		return () -> hostTask( idx, c::call, true );
	};

	//whether the game logic is parked right now (tests check where commands run)
	public static boolean atSafePoint(){
		return parked;
	}

	//actor thread, top of a scheduling pass: wait here while the render thread runs the tasks
	private static void parkForTasks(){
		if (tasks.isEmpty()) return;
		synchronized (park){
			parked = true;
			park.notifyAll();
			try {
				while (parked && Actor.keepActorThreadAlive){
					park.wait( 100 );
				}
			} catch (InterruptedException e){
				Thread.currentThread().interrupt();
			}
			parked = false;
		}
	}

	//a task just started a scene switch (an ankh's resurrection, the stairs): nothing may act on this
	//scene any more. A turn now would move Dungeon.hero (Hero.act points it at the acting hero), and
	//the next scene works on Dungeon.hero: InterlevelScene would resurrect the wrong hero. The old
	//scene's destroy() interrupts this thread; Actor.process then sees the interrupt and stops.
	private static void waitForSceneSwitch(){
		com.watabou.noosa.Scene before = Game.scene();
		long until = System.currentTimeMillis() + 10000;
		synchronized (park){
			try {
				while (Actor.keepActorThreadAlive && System.currentTimeMillis() < until
						&& (Game.switchingScene() || Game.scene() == before)){
					park.wait( 50 );
				}
			} catch (InterruptedException e){
				Thread.currentThread().interrupt();
			}
		}
	}

	//GameScene.update on the host: run the queued commands while the game logic is parked
	private static boolean resurrectPending(){
		return com.shatteredpixel.shatteredpixeldungeon.windows.WndResurrect.instance != null;
	}

	public static void hostRenderFrame(){
		if (!onlineHost()) return;
		//the game logic has stopped (everyone is dead, or the run waits for an ankh's choice):
		//it won't come back to a safe point, so what's queued runs straight away
		boolean stopped = (Coop.allDead() || resurrectPending()) && !Actor.processing();
		if (!parked && !stopped) return;
		//only what was queued before this frame: a task queued now (a retry, a deferred window)
		//waits for the next safe point, after the game has moved on
		int queued = tasks.size();
		Runnable r;
		//a task that started a scene switch (an ankh's resurrection, taking the stairs) ends the run:
		//the next scene works on Dungeon.hero as that task left it, so nothing else may move it now.
		//What's left runs at a safe point of the next scene.
		while (queued-- > 0 && !Game.switchingScene() && (r = tasks.poll()) != null){
			try {
				r.run();
			} catch (RuntimeException e){
				ShatteredPixelDungeon.reportException( e );
			}
		}
		synchronized (park){
			parked = false;
			park.notifyAll();
		}
	}

	// --- prompts for remote heroes ---

	private static int promptIds = 0;
	private static final java.util.Map<Integer, Object> prompts = new java.util.concurrent.ConcurrentHashMap<>();
	private static final java.util.Map<Integer, Hero> promptOwners = new java.util.concurrent.ConcurrentHashMap<>();

	private static boolean promptForRemote(){
		return onlineHost() && promptHero != null && promptHero != localHero();
	}

	private static int newPrompt( Object handler ){
		int id = ++promptIds;
		prompts.put( id, handler );
		promptOwners.put( id, promptHero );
		return id;
	}

	static void sendToHero( Hero h, Bundle m ){
		int idx = Coop.indexOf( h );
		if (idx > 0 && idx < slotsByPartyIndex.length) CoopOnline.host.send( slotsByPartyIndex[idx], m );
	}

	//GameScene.selectCell: a remote hero's targeting goes to its player
	public static boolean interceptCell( com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector.Listener listener ){
		if (!promptForRemote() || listener == null) return false;
		Bundle m = NetConnection.message( PROMPT_CELL );
		m.put( "id", newPrompt( listener ) );
		String text = listener.prompt();
		m.put( "text", text == null ? "" : text );
		sendToHero( promptHero, m );
		return true;
	}

	//the host player's own prompts: what they choose runs at a safe point too (as a task), not
	//straight from the UI while the actor thread may be running someone else's turn
	public static com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector.Listener localCell( com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector.Listener l ){
		if (!onlineHost() || !applyingCommand || l == null) return l;
		return new com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector.Listener() {
			@Override
			public void onSelect( Integer cell ) {
				hostTask( 0, () -> l.onSelect( cell ) );
			}
			@Override
			public String prompt() {
				return l.prompt();
			}
		};
	}

	public static com.shatteredpixel.shatteredpixeldungeon.windows.WndBag.ItemSelector localItem( com.shatteredpixel.shatteredpixeldungeon.windows.WndBag.ItemSelector s ){
		if (!onlineHost() || !applyingCommand || s == null) return s;
		return new com.shatteredpixel.shatteredpixeldungeon.windows.WndBag.ItemSelector() {
			@Override
			public String textPrompt() {
				return s.textPrompt();
			}
			@Override
			public Class<? extends com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag> preferredBag() {
				return s.preferredBag();
			}
			@Override
			public boolean hideAfterSelecting() {
				return s.hideAfterSelecting();
			}
			@Override
			public boolean itemSelectable( Item item ) {
				return s.itemSelectable( item );
			}
			@Override
			public void onSelect( Item item ) {
				hostTask( 0, () -> s.onSelect( item ) );
			}
		};
	}

	//GameScene.selectItem: a remote hero picks from its own items
	public static boolean interceptItem( com.shatteredpixel.shatteredpixeldungeon.windows.WndBag.ItemSelector selector ){
		if (!promptForRemote() || selector == null) return false;
		ArrayList<String> paths = new ArrayList<>();
		for (Item i : CoopItems.all( promptHero )){
			if (selector.itemSelectable( i )){
				String p = CoopItems.pathOf( promptHero, i );
				if (p != null) paths.add( p );
			}
		}
		Bundle m = NetConnection.message( PROMPT_ITEM );
		m.put( "id", newPrompt( selector ) );
		String text = selector.textPrompt();
		m.put( "text", text == null ? "" : text );
		m.put( "paths", paths.toArray( new String[0] ) );
		sendToHero( promptHero, m );
		return true;
	}

	//GameScene.show: a remote hero's windows go to its player, as option prompts
	public static boolean interceptWindow( com.shatteredpixel.shatteredpixeldungeon.ui.Window wnd ){
		if (!promptForRemote() || wnd == null) return false;
		//the window is never shown here: it must not catch the host's keys or keep a camera
		com.watabou.input.KeyEvent.removeKeyListener( wnd );
		if (wnd.camera != null) com.watabou.noosa.Camera.remove( wnd.camera );
		if (wnd instanceof com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions){
			com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions w = (com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions) wnd;
			String[] options = w.coopOptions == null ? new String[0] : w.coopOptions;
			boolean[] enabled = new boolean[options.length];
			for (int i = 0; i < options.length; i++) enabled[i] = optionEnabled( w, i );
			Bundle m = NetConnection.message( PROMPT_OPT );
			m.put( "id", newPrompt( w ) );
			synchronized (openWindows){ openWindows.put( wnd, promptHero ); }
			m.put( "title", w.coopTitle == null ? "" : w.coopTitle );
			m.put( "text", w.coopMessage == null ? "" : w.coopMessage );
			m.put( "options", options );
			m.put( "enabled", enabled );
			sendToHero( promptHero, m );
		} else {
			//any other window: its texts and buttons (item buttons too) become an option prompt,
			//and the answer clicks that button on the (never shown) window here. A window without
			//buttons (quest texts) gets an OK that closes it.
			ArrayList<com.watabou.noosa.ui.Component> buttons = new ArrayList<>();
			ArrayList<String> texts = new ArrayList<>();
			collectUi( wnd, buttons, texts );
			String[] options = new String[Math.max( 1, buttons.size() )];
			boolean[] enabled = new boolean[options.length];
			if (buttons.isEmpty()){
				options[0] = Messages.get( CoopRemote.class, "ok" );
				enabled[0] = true;
			}
			for (int i = 0; i < buttons.size(); i++){
				options[i] = label( buttons.get( i ) );
				enabled[i] = buttons.get( i ).active;
			}
			StringBuilder body = new StringBuilder();
			for (int i = 1; i < texts.size(); i++){
				if (body.length() > 0) body.append( "\n" );
				body.append( texts.get( i ) );
			}
			Bundle m = NetConnection.message( PROMPT_OPT );
			m.put( "id", newPrompt( new WindowButtons( wnd, buttons ) ) );
			synchronized (openWindows){ openWindows.put( wnd, promptHero ); }
			m.put( "title", texts.isEmpty() ? "" : texts.get( 0 ) );
			m.put( "text", body.toString() );
			m.put( "options", options );
			m.put( "enabled", enabled );
			sendToHero( promptHero, m );
		}
		return true;
	}

	//forwarded windows, by the hero whose player has them. Some stay open after a button (they
	//update themselves, e.g. WndResurrect after choosing an item to keep): once that hero has no
	//other question pending, its last open window is sent again as it is now.
	private static final java.util.LinkedHashMap<com.shatteredpixel.shatteredpixeldungeon.ui.Window, Hero> openWindows = new java.util.LinkedHashMap<>();

	private static void resendOpenWindow( Hero h ){
		com.shatteredpixel.shatteredpixeldungeon.ui.Window last = null;
		synchronized (openWindows){
			//a closed window has been destroyed: no members left
			openWindows.keySet().removeIf( w -> w.length == 0 );
			if (promptOwners.containsValue( h )) return;
			for (java.util.Map.Entry<com.shatteredpixel.shatteredpixeldungeon.ui.Window, Hero> e : openWindows.entrySet()){
				if (e.getValue() == h) last = e.getKey();
			}
			if (last == null) return;
			openWindows.remove( last );
		}
		interceptWindow( last );
	}

	private static class WindowButtons {
		final com.shatteredpixel.shatteredpixeldungeon.ui.Window window;
		final ArrayList<com.watabou.noosa.ui.Component> buttons;
		WindowButtons( com.shatteredpixel.shatteredpixeldungeon.ui.Window w, ArrayList<com.watabou.noosa.ui.Component> b ){
			window = w;
			buttons = b;
		}
	}

	private static String label( com.watabou.noosa.ui.Component b ){
		if (b instanceof com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton){
			return ((com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton) b).text();
		}
		if (b instanceof com.shatteredpixel.shatteredpixeldungeon.ui.TalentButton){
			com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent t = talentOf( (com.shatteredpixel.shatteredpixeldungeon.ui.TalentButton) b );
			return t == null ? "?" : Messages.titleCase( t.title() );
		}
		Item item = ((com.shatteredpixel.shatteredpixeldungeon.ui.ItemButton) b).item();
		return item == null ? "?" : Messages.titleCase( item.title() );
	}

	private static com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent talentOf( com.shatteredpixel.shatteredpixeldungeon.ui.TalentButton b ){
		try {
			java.lang.reflect.Field f = com.shatteredpixel.shatteredpixeldungeon.ui.TalentButton.class.getDeclaredField( "talent" );
			f.setAccessible( true );
			return (com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent) f.get( b );
		} catch (Exception e){
			return null;
		}
	}

	//texts and buttons of a window, in layout order
	private static void collectUi( com.watabou.noosa.Group g, ArrayList<com.watabou.noosa.ui.Component> buttons, ArrayList<String> texts ){
		java.util.List<?> members;
		try {
			java.lang.reflect.Field f = com.watabou.noosa.Group.class.getDeclaredField( "members" );
			f.setAccessible( true );
			members = new ArrayList<>( (java.util.List<?>) f.get( g ) );
		} catch (Exception e){
			return;
		}
		for (Object o : members){
			if (!(o instanceof com.watabou.noosa.Gizmo)) continue;
			com.watabou.noosa.Gizmo m = (com.watabou.noosa.Gizmo) o;
			if (m == null || !m.exists) continue;
			if (m instanceof com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton){
				com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton b = (com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton) m;
				if (b.visible && b.text() != null && !b.text().isEmpty()) buttons.add( b );
			} else if (m instanceof com.shatteredpixel.shatteredpixeldungeon.ui.TalentButton){
				//talents to pick (metamorphosis)
				com.shatteredpixel.shatteredpixeldungeon.ui.TalentButton b = (com.shatteredpixel.shatteredpixeldungeon.ui.TalentButton) m;
				if (b.visible && b.active && talentOf( b ) != null) buttons.add( b );
			} else if (m instanceof com.shatteredpixel.shatteredpixeldungeon.ui.ItemButton){
				com.shatteredpixel.shatteredpixeldungeon.ui.ItemButton b = (com.shatteredpixel.shatteredpixeldungeon.ui.ItemButton) m;
				if (b.visible && b.item() != null) buttons.add( b );
			} else if (m instanceof com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock){
				String t = ((com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock) m).text();
				if (t != null && !t.isEmpty()) texts.add( t );
			} else if (m instanceof com.watabou.noosa.Group){
				collectUi( (com.watabou.noosa.Group) m, buttons, texts );
			}
		}
	}

	private static void click( com.watabou.noosa.ui.Component b ){
		for (Class<?> c = b.getClass(); c != null; c = c.getSuperclass()){
			try {
				java.lang.reflect.Method m = c.getDeclaredMethod( "onClick" );
				m.setAccessible( true );
				m.invoke( b );
				return;
			} catch (NoSuchMethodException e){
				//look further up
			} catch (Exception e){
				ShatteredPixelDungeon.reportException( e );
				return;
			}
		}
	}

	private static boolean optionEnabled( com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions w, int i ){
		try {
			java.lang.reflect.Method m = com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions.class.getDeclaredMethod( "enabled", int.class );
			m.setAccessible( true );
			return (Boolean) m.invoke( w, i );
		} catch (Exception e){
			return true;
		}
	}

	private static void answerPrompt( Hero h, Bundle a ){
		int id = a.getInt( "id" );
		Object handler = prompts.remove( id );
		Hero owner = promptOwners.remove( id );
		if (handler == null || owner != h) return;
		if (handler instanceof com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector.Listener){
			int c = a.getInt( "cell" );
			boolean onMap = Dungeon.level != null && c >= 0 && c < Dungeon.level.length();
			((com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector.Listener) handler).onSelect( onMap ? c : null );
		} else if (handler instanceof com.shatteredpixel.shatteredpixeldungeon.windows.WndBag.ItemSelector){
			Item item = a.contains( "path" ) ? CoopItems.at( h, a.getString( "path" ) ) : null;
			com.shatteredpixel.shatteredpixeldungeon.windows.WndBag.ItemSelector sel = (com.shatteredpixel.shatteredpixeldungeon.windows.WndBag.ItemSelector) handler;
			if (item != null && !sel.itemSelectable( item )) item = null;
			sel.onSelect( item );
		} else if (handler instanceof WindowButtons){
			int index = a.getInt( "index" );
			WindowButtons wb = (WindowButtons) handler;
			if (index < 0) wb.window.onBackPressed();
			else if (wb.buttons.isEmpty()) wb.window.hide();
			else if (index < wb.buttons.size()){
				//the window is never shown here, so it never updates: some enable their buttons
				//there (e.g. once the hero is ready)
				wb.window.update();
				if (wb.buttons.get( index ).active){
					click( wb.buttons.get( index ) );
				} else if (a.getInt( "tries" ) < 300){
					//not yet (the hero is still finishing a step): try again at the next safe points
					prompts.put( id, handler );
					promptOwners.put( id, owner );
					a.put( "tries", a.getInt( "tries" ) + 1 );
					final int idx = Coop.indexOf( h );
					hostTask( idx, () -> answerPrompt( Coop.get( idx ), a ) );
				}
			}
		} else if (handler instanceof com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions){
			int index = a.getInt( "index" );
			com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions w = (com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions) handler;
			if (index < 0){
				w.onBackPressed();
				return;
			}
			//only options the window offers, and only enabled ones (e.g. buyback the party can afford)
			int count = w.coopOptions == null ? 0 : w.coopOptions.length;
			if (index >= count || !optionEnabled( w, index )) return;
			try {
				java.lang.reflect.Method m = com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions.class.getDeclaredMethod( "onSelect", int.class );
				m.setAccessible( true );
				//like its button: the window closes, then the choice runs
				w.hide();
				m.invoke( handler, index );
			} catch (Exception e){
				ShatteredPixelDungeon.reportException( e );
			}
		}
	}

	//the host's save files, as clients load them
	private static Bundle snapshot( int seqValue ){
		try {
			Dungeon.saveAll();
		} catch (IOException e){
			ShatteredPixelDungeon.reportException( e );
			return null;
		}
		int slot = GamesInProgress.curSlot;
		byte[] game = FileUtils.getFileHandle( GamesInProgress.gameFile( slot ) ).readBytes();
		byte[] level = FileUtils.getFileHandle( GamesInProgress.depthFile( slot, Dungeon.depth, Dungeon.branch ) ).readBytes();

		Bundle m = NetConnection.message( SNAP );
		m.put( "seq", seqValue );
		m.put( "game", Base64.getEncoder().encodeToString( game ) );
		m.put( "level", Base64.getEncoder().encodeToString( level ) );
		m.put( "depth", Dungeon.depth );
		m.put( "branch", Dungeon.branch );
		m.put( "party", slotsByPartyIndex );
		return m;
	}

	private static void sendSnapshot(){
		snapshotNeeded = false;
		Bundle m = snapshot( seq + 1 );
		if (m == null) return;
		seq++;
		CoopOnline.host.broadcast( m );
		//prompts from before (another level) can't be answered any more
		prompts.clear();
		promptOwners.clear();
		synchronized (openWindows){
			openWindows.clear();
		}

		sentLevel = Dungeon.level;
		sentIds.clear();
		for (Char ch : Actor.chars()) sentIds.add( ch.id() );
		mapHash = Arrays.hashCode( Dungeon.level.map );
		heapHash = heapsBundle().toString().hashCode();
		blobHash = blobsBundle( Dungeon.level ).toString().hashCode();
		heroHash = new int[Coop.size()];
		lastUpdate = System.currentTimeMillis();
	}

	private static Bundle heapsBundle(){
		Bundle b = new Bundle();
		b.put( "heaps", Dungeon.level.heaps.valueList() );
		return b;
	}

	private static void sendUpdate(){
		lastUpdate = System.currentTimeMillis();
		Level level = Dungeon.level;
		Bundle m = NetConnection.message( UPD );
		m.put( "seq", seq );

		ArrayList<Char> chars = new ArrayList<>( Actor.chars() );
		int n = chars.size();
		int[] ids = new int[n], pos = new int[n], hp = new int[n], ht = new int[n];
		String[] buffs = new String[n];
		ArrayList<Char> fresh = new ArrayList<>();
		for (int i = 0; i < n; i++){
			Char ch = chars.get(i);
			ids[i] = ch.id();
			pos[i] = ch.pos;
			hp[i] = ch.HP;
			ht[i] = ch.HT;
			StringBuilder sb = new StringBuilder();
			for (Buff b : ch.buffs()){
				if (sb.length() > 0) sb.append( ',' );
				sb.append( b.getClass().getName() );
			}
			buffs[i] = sb.toString();
			if (!sentIds.contains( ch.id() )){
				fresh.add( ch );
				sentIds.add( ch.id() );
			}
		}
		m.put( "ids", ids );
		m.put( "pos", pos );
		m.put( "hp", hp );
		m.put( "ht", ht );
		m.put( "buffs", buffs );
		m.put( "chk", checksum( ids, pos, hp ) );
		if (!fresh.isEmpty()) m.put( "new", fresh );

		int mh = Arrays.hashCode( level.map );
		if (mh != mapHash){
			mapHash = mh;
			m.put( "map", level.map );
		}

		Bundle heaps = heapsBundle();
		int hh = heaps.toString().hashCode();
		if (hh != heapHash){
			heapHash = hh;
			m.put( "heaps", heaps );
		}

		boolean[] waiting = new boolean[Coop.size()];
		for (int i = 0; i < waiting.length; i++){
			Hero h = Coop.get( i );
			waiting[i] = h.isAlive() && !Coop.isDead( h ) && CoopTime.isWaiting( h );
		}
		m.put( "waiting", waiting );
		Bundle blobs = blobsBundle( level );
		int bh = blobs.toString().hashCode();
		if (bh != blobHash){
			blobHash = bh;
			m.put( "blobs", blobs );
		}

		//shared by the party
		m.put( "gold", Dungeon.gold );
		m.put( "energy", Dungeon.energy );

		if (lastUpdate - lastHeroBundles >= HERO_BUNDLE_MS){
			lastHeroBundles = lastUpdate;
			if (heroHash.length != Coop.size()) heroHash = new int[Coop.size()];
			for (int i = 0; i < Coop.size(); i++){
				Bundle hb = new Bundle();
				hb.put( "h", Coop.get( i ) );
				int hash = hb.toString().hashCode();
				if (hash != heroHash[i]){
					heroHash[i] = hash;
					m.put( "hero" + i, hb );
				}
			}
		}

		ArrayList<String> ev = new ArrayList<>();
		String e;
		while ((e = events.poll()) != null) ev.add( e );
		if (!ev.isEmpty()) m.put( "ev", ev.toArray( new String[0] ) );

		CoopOnline.host.broadcast( m );
	}

	private static int blobHash = 0;

	//gases and other floor effects: for each kind, the cells it is on and how strong it is there
	private static Bundle blobsBundle( Level level ){
		Bundle b = new Bundle();
		ArrayList<String> kinds = new ArrayList<>();
		for (com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob blob : level.blobs.values()){
			if (blob.volume <= 0 || blob.cur == null) continue;
			int n = 0;
			for (int v : blob.cur) if (v > 0) n++;
			int[] cells = new int[n], vals = new int[n];
			n = 0;
			for (int i = 0; i < blob.cur.length; i++){
				if (blob.cur[i] > 0){
					cells[n] = i;
					vals[n] = blob.cur[i];
					n++;
				}
			}
			String kind = blob.getClass().getName();
			kinds.add( kind );
			b.put( kind + ".c", cells );
			b.put( kind + ".v", vals );
		}
		b.put( "kinds", kinds.toArray( new String[0] ) );
		return b;
	}

	public static int checksum( int[] ids, int[] pos, int[] hp ){
		long sum = 0;
		for (int i = 0; i < ids.length; i++){
			sum += (long)(ids[i] * 31 + pos[i]) * 17 + hp[i] * 7L + (long)ids[i] * pos[i];
		}
		return (int)(sum ^ (sum >>> 32));
	}

	//sprite and log events of the host's game, replayed by clients
	public static void spriteEvent( String type, Char ch, int cell ){
		if (onlineHost() && ch != null) events.add( type + "|" + ch.id() + "|" + cell );
	}

	public static void statusEvent( Char ch, int color, String text ){
		if (onlineHost() && ch != null) events.add( "st|" + ch.id() + "|" + color + "|" + text );
	}

	public static void logEvent( String text ){
		if (onlineHost()) events.add( "log|" + text );
	}

	//CoopTime.afterAct: the host's screen keeps showing its own hero
	public static void afterHeroAct( Actor acting, boolean done ){
		if (!onlineHost() || !(acting instanceof Hero)) return;
		Hero me = localHero();
		if (acting != me && me != null && (done || CoopTime.isWaiting( (Hero) acting ))) Coop.setActive( me );
	}

	// ---------------------------------------------------------------------------------
	// Client
	// ---------------------------------------------------------------------------------

	private static volatile boolean clientInGame = false;
	private static final ConcurrentLinkedQueue<Bundle> incoming = new ConcurrentLinkedQueue<>();
	private static int clientSeq = -1;
	private static boolean resyncAsked = false;

	//checksums of the last update: the host's and the mirror's after applying it (tests read these)
	public static volatile int lastHostChecksum = 0;
	public static volatile int lastMirrorChecksum = 0;
	public static volatile int updatesApplied = 0;
	public static volatile int checksumMismatches = 0;
	public static volatile String lastMismatch = "";
	//host: what happened to the last item command (tests and debugging)
	public static volatile String lastUseResult = "";
	//commands the host dropped: made for another level, or not on the hero's turn (tests read these)
	public static volatile int droppedStale = 0, droppedNotWaiting = 0;

	public static void startClient(){
		clientInGame = false;
		clientSeq = -1;
		incoming.clear();
		gaveUp = false;
		reconnecting = false;
		reconnectUntil = 0;
		CoopOnline.client.setEvents( new CoopClient.Events() {
			@Override
			public void onGameMessage( Bundle message ) {
				incoming.add( message );
			}

			@Override
			public void onWelcome( int slot ) {
				//only seen again after reconnecting
				incoming.add( NetConnection.message( "rejoined" ) );
			}

			@Override
			public void onRejected( String reason ) {
				//while reconnecting the host may not have noticed the drop yet: keep trying
				incoming.add( NetConnection.message( reconnectUntil != 0 ? "retry" : "gaveup" ) );
			}

			@Override
			public void onDisconnected( String reason ) {
				Bundle b = NetConnection.message( "lost" );
				b.put( "reason", reason );
				incoming.add( b );
			}
		});
		ShatteredPixelDungeon.scene().addToFront( new WndMessage( Messages.get( CoopRemote.class, "waiting" ) ) );
	}

	//render thread, every frame (lobby, loading and game scenes)
	public static void clientFrame(){
		if (!CoopOnline.isClient()) return;
		Bundle m;
		while ((m = incoming.peek()) != null){
			String type = NetConnection.type( m );
			if (type.equals( SNAP )){
				//one snapshot at a time: a newer one waits until the last one has loaded
				if (Game.scene() instanceof InterlevelScene) return;
				incoming.poll();
				awaitingSnapshot = false;
				loadSnapshot( m );
				//updates for this snapshot are applied once its scene is up
				return;
			}
			if (type.equals( "lost" )){
				incoming.poll();
				//the host ending the session (bye) or this player leaving isn't a dropped connection
				String reason = m.getString( "reason" );
				boolean closed = "host closed".equals( reason ) || "left".equals( reason );
				if (!closed && clientInGame && CoopOnline.client != null && CoopOnline.client.canRejoin()){
					startReconnecting();
					continue;
				}
				giveUp();
				return;
			}
			if (type.equals( "retry" )){
				incoming.poll();
				//the connection closes next ("lost"), which starts the next attempt
				reconnecting = false;
				continue;
			}
			if (type.equals( "gaveup" )){
				incoming.poll();
				giveUp();
				return;
			}
			if (type.equals( "rejoined" )){
				incoming.poll();
				reconnecting = false;
				reconnectUntil = 0;
				//what the host sends before our snapshot is about things this mirror missed
				awaitingSnapshot = true;
				reconnects++;
				if (reconnectWindow != null){
					reconnectWindow.hide();
					reconnectWindow = null;
				}
				GLog.p( Messages.get( CoopRemote.class, "reconnected" ) );
				continue;
			}
			//(loadingSnapshot: the scene switch only happens next frame, the mirror is still the old one)
			if (!(Game.scene() instanceof GameScene) || Dungeon.level == null || !clientInGame || loadingSnapshot) return;
			incoming.poll();
			switch (type){
				case UPD:
					if (m.getInt( "seq" ) == clientSeq && !awaitingSnapshot) applyUpdate( m );
					break;
				case PROMPT_CELL: case PROMPT_ITEM: case PROMPT_OPT:
					showPrompt( type, m );
					break;
				case "notice":
					GLog.w( m.getString( "text" ) );
					break;
				case CoopCommands.ALCH_OPEN:
					CoopCommands.showAlchemy( m );
					//the rest waits until the player is back from the alchemy screen
					return;
			}
		}
	}

	// --- reconnecting after the connection dropped ---

	private static final long RECONNECT_FOR_MS = 60_000;
	private static final long RECONNECT_EVERY_MS = 2_000;
	private static volatile boolean reconnecting = false;
	//when this client stops trying to get back in (0: not reconnecting)
	private static volatile long reconnectUntil = 0;
	private static boolean awaitingSnapshot = false;
	private static volatile boolean loadingSnapshot = false;

	//client state for test reports
	public static String clientDebug(){
		Hero me = localHero();
		return "scene=" + Game.scene().getClass().getSimpleName() + " inGame=" + clientInGame + " loading=" + loadingSnapshot
				+ " awaitingSnap=" + awaitingSnapshot + " reconnecting=" + reconnecting + " seq=" + clientSeq
				+ " queued=" + incoming.size() + (incoming.peek() == null ? "" : " next=" + NetConnection.type( incoming.peek() ) + "/" + incoming.peek().getInt( "seq" ))
				+ " ready=" + (me != null && me.ready) + " connected=" + (CoopOnline.client != null && CoopOnline.client.isConnected())
				+ " updates=" + updatesApplied + " waiting=" + lastWaiting + " prompt=" + lastPromptType + "/" + lastPromptId
				+ " me=P" + (Coop.indexOf( me ) + 1) + " dungeonHero=P" + (Coop.indexOf( Dungeon.hero ) + 1);
	}
	private static WndMessage reconnectWindow = null;
	//times this client got back in (tests read it)
	public static volatile int reconnects = 0;

	private static void startReconnecting(){
		if (reconnecting) return;
		reconnecting = true;
		if (reconnectUntil == 0) reconnectUntil = System.currentTimeMillis() + RECONNECT_FOR_MS;
		Hero me = localHero();
		if (me != null) me.ready = false;
		if (reconnectWindow == null){
			reconnectWindow = new WndMessage( Messages.get( CoopRemote.class, "reconnecting" ) );
			ShatteredPixelDungeon.scene().addToFront( reconnectWindow );
		}
		final CoopClient client = CoopOnline.client;
		Thread t = new Thread( () -> {
			while (System.currentTimeMillis() < reconnectUntil && CoopOnline.client == client){
				try {
					Thread.sleep( RECONNECT_EVERY_MS );
					client.reconnect( (int) RECONNECT_EVERY_MS );
					//the host answers with welcome (or reject: lost again) and a snapshot
					return;
				} catch (IOException e){
					//host not reachable yet
				} catch (InterruptedException e){
					return;
				}
			}
			incoming.add( NetConnection.message( "gaveup" ) );
		}, "coop-reconnect" );
		t.setDaemon( true );
		t.start();
	}

	private static boolean gaveUp = false;

	private static void giveUp(){
		if (gaveUp) return;
		gaveUp = true;
		reconnecting = false;
		reconnectUntil = 0;
		clientInGame = false;
		if (reconnectWindow != null){
			reconnectWindow.hide();
			reconnectWindow = null;
		}
		CoopOnline.leave();
		ShatteredPixelDungeon.scene().addToFront( new WndMessage( Messages.get( CoopRemote.class, "lost" ) ) );
	}

	private static void loadSnapshot( Bundle m ){
		clientSeq = m.getInt( "seq" );
		slotsByPartyIndex = m.getIntArray( "party" );
		int slot = CLIENT_SLOT;
		FileUtils.getFileHandle( GamesInProgress.gameFile( slot ) ).writeBytes( Base64.getDecoder().decode( m.getString( "game" ) ), false );
		FileUtils.getFileHandle( GamesInProgress.depthFile( slot, m.getInt( "depth" ), m.getInt( "branch" ) ) )
				.writeBytes( Base64.getDecoder().decode( m.getString( "level" ) ), false );
		GamesInProgress.setUnknown( slot );
		GamesInProgress.curSlot = slot;
		clientInGame = true;
		loadingSnapshot = true;
		resyncAsked = false;
		InterlevelScene.mode = InterlevelScene.Mode.CONTINUE;
		Game.switchScene( InterlevelScene.class );
	}

	//CoopCore.onGameLoaded: the client plays its own hero
	public static void afterGameLoaded(){
		if (!onlineClient()) return;
		loadingSnapshot = false;
		int i = localPartyIndex();
		if (i >= 0 && i < Coop.size()) Dungeon.hero = Coop.get( i );
		Actor.coopClientIds();
	}

	private static void applyUpdate( Bundle m ){
		Level level = Dungeon.level;

		if (m.contains( "map" )){
			int[] map = m.getIntArray( "map" );
			if (map != null && map.length == level.length()){
				for (int i = 0; i < map.length; i++){
					if (level.map[i] != map[i]){
						Level.set( i, map[i], level );
						GameScene.updateMap( i );
					}
				}
			}
		}

		if (m.contains( "heaps" )){
			for (Heap h : level.heaps.valueList()){
				if (h.sprite != null) h.sprite.kill();
			}
			level.heaps.clear();
			for (Bundlable b : m.getBundle( "heaps" ).getCollection( "heaps" )){
				Heap h = (Heap) b;
				if (h == null || h.isEmpty()) continue;
				level.heaps.put( h.pos, h );
				GameScene.add( h );
			}
		}

		if (m.contains( "new" )){
			for (Bundlable b : m.getCollection( "new" )){
				if (!(b instanceof Mob)) continue;
				Mob mob = (Mob) b;
				if (Actor.findById( mob.id() ) != null) continue;
				level.mobs.add( mob );
				Actor.add( mob );
				GameScene.addSprite( mob );
			}
		}

		int[] ids = m.getIntArray( "ids" );
		int[] pos = m.getIntArray( "pos" );
		int[] hp = m.getIntArray( "hp" );
		int[] ht = m.getIntArray( "ht" );
		String[] buffs = m.getStringArray( "buffs" );
		HashSet<Integer> present = new HashSet<>();
		for (int i = 0; i < ids.length; i++){
			present.add( ids[i] );
			Actor a = Actor.findById( ids[i] );
			if (!(a instanceof Char)){
				askResync();
				continue;
			}
			Char ch = (Char) a;
			int old = ch.pos;
			ch.pos = pos[i];
			ch.HP = hp[i];
			ch.HT = ht[i];
			if (ch.sprite != null && old != pos[i]){
				if (level.adjacent( old, pos[i] )) ch.sprite.move( old, pos[i] );
				else ch.sprite.place( pos[i] );
			}
			if (!(ch instanceof Hero)) syncBuffs( ch, buffs[i] );
		}

		//chars the host no longer has: gone (killed, left the level...)
		for (Char ch : Actor.chars()){
			if (ch instanceof Hero || present.contains( ch.id() )) continue;
			if (ch instanceof Mob) level.mobs.remove( ch );
			Actor.remove( ch );
			if (ch.sprite != null) ch.sprite.die();
		}

		for (int i = 0; i < Coop.size(); i++){
			if (!m.contains( "hero" + i )) continue;
			Hero mirror = Coop.get( i );
			Hero fresh = (Hero) m.getBundle( "hero" + i ).get( "h" );
			if (fresh != null){
				try {
					copyHero( fresh, mirror );
				} catch (RuntimeException ex){
					//one bad hero bundle must not stop the rest of the update (waiting flags...)
					lastReplayError = "hero " + i + ": " + ex;
					ShatteredPixelDungeon.reportException( ex );
				}
			}
		}

		boolean[] waiting = m.getBooleanArray( "waiting" );
		lastWaiting = Arrays.toString( waiting );
		if (waiting != null){
			for (int i = 0; i < Math.min( waiting.length, Coop.size() ); i++){
				Coop.get( i ).ready = waiting[i];
			}
		}

		if (m.contains( "blobs" )) applyBlobs( level, m.getBundle( "blobs" ) );

		if (m.contains( "gold" )){
			Dungeon.gold = m.getInt( "gold" );
			Dungeon.energy = m.getInt( "energy" );
		}

		if (m.contains( "ev" )){
			for (String e : m.getStringArray( "ev" )) replayEvent( e );
		}

		Dungeon.observe();

		updatesApplied++;
		lastHostChecksum = m.getInt( "chk" );
		int[] mp = new int[ids.length], mh = new int[ids.length];
		for (int i = 0; i < ids.length; i++){
			Actor a = Actor.findById( ids[i] );
			mp[i] = a instanceof Char ? ((Char) a).pos : -1;
			mh[i] = a instanceof Char ? ((Char) a).HP : -1;
		}
		lastMirrorChecksum = checksum( ids, mp, mh );
		if (lastMirrorChecksum != lastHostChecksum){
			checksumMismatches++;
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < ids.length && sb.length() < 300; i++){
				if (mp[i] != pos[i] || mh[i] != hp[i]){
					Actor a = Actor.findById( ids[i] );
					sb.append( a == null ? "missing" : a.getClass().getSimpleName() ).append( "#" ).append( ids[i] )
							.append( " host " ).append( pos[i] ).append( "/" ).append( hp[i] )
							.append( " mirror " ).append( mp[i] ).append( "/" ).append( mh[i] ).append( "; " );
				}
			}
			lastMismatch = sb.toString();
		}
	}

	//the mirror's gases take the host's cells; they never act here, they're only drawn
	private static void applyBlobs( Level level, Bundle b ){
		HashSet<String> present = new HashSet<>( Arrays.asList( b.getStringArray( "kinds" ) ) );
		for (com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob blob : level.blobs.values()){
			if (!present.contains( blob.getClass().getName() ) && blob.volume > 0 && blob.cur != null){
				Arrays.fill( blob.cur, 0 );
				blob.volume = 0;
				blob.area.setEmpty();
			}
		}
		for (String kind : present){
			Class<?> cls = Reflection.forName( kind );
			if (cls == null || !com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob.class.isAssignableFrom( cls )) continue;
			@SuppressWarnings("unchecked")
			Class<? extends com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob> bc = (Class<? extends com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob>) cls;
			com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob blob = level.blobs.get( bc );
			if (blob == null){
				blob = Reflection.newInstance( bc );
				if (blob == null) continue;
				level.blobs.put( bc, blob );
				GameScene.add( blob );
			}
			if (blob.cur == null || blob.cur.length != level.length()) blob.cur = new int[level.length()];
			Arrays.fill( blob.cur, 0 );
			int[] cells = b.getIntArray( kind + ".c" ), vals = b.getIntArray( kind + ".v" );
			int volume = 0;
			blob.area.setEmpty();
			for (int i = 0; i < cells.length && i < vals.length; i++){
				if (cells[i] < 0 || cells[i] >= blob.cur.length) continue;
				blob.cur[cells[i]] = vals[i];
				volume += vals[i];
				blob.area.union( cells[i] % level.width(), cells[i] / level.width() );
			}
			blob.volume = volume;
		}
		blobsApplied++;
	}

	//updates that carried gases (tests read it)
	public static volatile int blobsApplied = 0;
	//missiles and bolts drawn from the host's events (tests read it)
	public static volatile int missilesReplayed = 0;
	public static volatile String lastReplayError = "";
	public static volatile String lastWaiting = "";

	//MissileSprite / MagicMissile: thrown things and magic bolts, drawn on clients without callbacks
	public static void missileEvent( Object item, float fx, float fy, float tx, float ty ){
		if (!onlineHost()) return;
		events.add( "ms|" + (item == null ? "" : item.getClass().getName()) + "|" + Math.round( fx ) + "|" + Math.round( fy ) + "|" + Math.round( tx ) + "|" + Math.round( ty ) );
	}

	public static volatile int missilesSent = 0;

	public static void boltEvent( int type, float fx, float fy, float tx, float ty ){
		if (!onlineHost()) return;
		missilesSent++;
		events.add( "mm|" + type + "|" + Math.round( fx ) + "|" + Math.round( fy ) + "|" + Math.round( tx ) + "|" + Math.round( ty ) );
	}

	private static void replayMissile( String[] p ){
		Hero me = localHero();
		if (me == null || me.sprite == null || me.sprite.parent == null){
			lastReplayError = "no sprite to draw " + p[0] + " with";
			return;
		}
		com.watabou.utils.PointF from = new com.watabou.utils.PointF( Integer.parseInt( p[2] ), Integer.parseInt( p[3] ) );
		com.watabou.utils.PointF to = new com.watabou.utils.PointF( Integer.parseInt( p[4] ), Integer.parseInt( p[5] ) );
		if (p[0].equals( "ms" )){
			Item item = null;
			Class<?> cls = p[1].isEmpty() ? null : Reflection.forName( p[1] );
			if (cls != null && Item.class.isAssignableFrom( cls )) item = (Item) Reflection.newInstance( cls );
			((com.shatteredpixel.shatteredpixeldungeon.sprites.MissileSprite) me.sprite.parent.recycle(
					com.shatteredpixel.shatteredpixeldungeon.sprites.MissileSprite.class )).reset( from, to, item, null );
		} else {
			((com.shatteredpixel.shatteredpixeldungeon.effects.MagicMissile) me.sprite.parent.recycle(
					com.shatteredpixel.shatteredpixeldungeon.effects.MagicMissile.class )).reset( Integer.parseInt( p[1] ), from, to, null );
		}
		missilesReplayed++;
	}

	private static void askResync(){
		if (resyncAsked) return;
		resyncAsked = true;
		CoopOnline.client.send( NetConnection.message( RESYNC ) );
	}

	private static void syncBuffs( Char ch, String csv ){
		HashSet<String> want = new HashSet<>();
		if (csv != null && !csv.isEmpty()) want.addAll( Arrays.asList( csv.split( "," ) ) );
		for (Buff b : ch.buffs()){
			if (!want.remove( b.getClass().getName() )) b.detach();
		}
		for (String cls : want){
			Class<?> c = Reflection.forName( cls );
			if (c == null || !Buff.class.isAssignableFrom( c )) continue;
			Buff b = (Buff) Reflection.newInstance( c );
			if (b != null) b.attachTo( ch );
		}
	}

	//the mirror hero takes over the host hero's state (belongings, stats, buffs), keeping its sprite
	private static void copyHero( Hero from, Hero to ){
		to.HP = from.HP;
		to.HT = from.HT;
		to.lvl = from.lvl;
		to.exp = from.exp;
		to.STR = from.STR;
		to.pos = from.pos;
		to.subClass = from.subClass;
		to.armorAbility = from.armorAbility;
		to.talents = from.talents;
		to.metamorphedTalents = from.metamorphedTalents;
		boolean mine = to == localHero();
		String[] slots = new String[com.shatteredpixel.shatteredpixeldungeon.QuickSlot.SIZE];
		if (mine){
			for (int i = 0; i < slots.length; i++){
				Item q = Dungeon.quickslot.getItem( i );
				slots[i] = q == null ? null : CoopItems.pathOf( to, q );
			}
		}
		Bundle b = new Bundle();
		from.belongings.storeInBundle( b );
		to.belongings.restoreFromBundle( b );
		if (mine){
			for (int i = 0; i < slots.length; i++){
				Item now = slots[i] == null ? null : CoopItems.at( to, slots[i] );
				if (now != null) Dungeon.quickslot.setSlot( i, now );
				else if (slots[i] != null) Dungeon.quickslot.clearSlot( i );
			}
		}
		for (Buff buff : to.buffs()) buff.detach();
		for (Buff buff : from.buffs()){
			buff.detach();
			buff.attachTo( to );
		}
		if (to == localHero()){
			Item.updateQuickslot();
			BuffIndicator.refreshHero();
		}
	}

	private static void replayEvent( String e ){
		String[] p = e.split( "\\|", 4 );
		try {
			if (p[0].equals( "log" )){
				GLog.i( e.substring( 4 ) );
				return;
			}
			if (p[0].equals( "ms" ) || p[0].equals( "mm" )){
				replayMissile( e.split( "\\|", -1 ) );
				return;
			}
			Actor a = Actor.findById( Integer.parseInt( p[1] ) );
			if (!(a instanceof Char) || ((Char) a).sprite == null) return;
			Char ch = (Char) a;
			switch (p[0]){
				case "atk": case "zap": case "op":
					ch.sprite.coopPlay( p[0], Integer.parseInt( p[2] ) );
					break;
				case "st":  ch.sprite.showStatus( Integer.parseInt( p[2] ), p.length > 3 ? p[3] : "" ); break;
			}
		} catch (RuntimeException ex){
			//a malformed or stale event only loses an animation
			lastReplayError = e + ": " + ex;
		}
	}

	//the prompt the host asked this client last (tests answer it directly)
	public static volatile String lastPromptType = null;
	public static volatile int lastPromptId = -1;
	public static volatile String[] lastPromptPaths = new String[0];
	public static volatile String[] lastPromptOptions = new String[0];
	public static volatile String lastPromptTitle = "";
	public static volatile int promptsShown = 0;

	private static void showPrompt( String type, Bundle m ){
		final int id = m.getInt( "id" );
		final String text = m.getString( "text" );
		if (type.equals( PROMPT_OPT )){
			lastPromptOptions = m.getStringArray( "options" );
			lastPromptTitle = m.getString( "title" );
		}
		lastPromptType = type;
		lastPromptId = id;
		promptsShown++;
		switch (type){
			case PROMPT_CELL:
				GameScene.selectCell( new com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector.Listener() {
					@Override
					public void onSelect( Integer cell ) {
						answerCell( id, cell == null ? -1 : cell );
					}
					@Override
					public String prompt() {
						return text;
					}
				});
				break;
			case PROMPT_ITEM:
				final HashSet<String> paths = new HashSet<>( Arrays.asList( m.getStringArray( "paths" ) ) );
				lastPromptPaths = paths.toArray( new String[0] );
				GameScene.selectItem( new com.shatteredpixel.shatteredpixeldungeon.windows.WndBag.ItemSelector() {
					@Override
					public String textPrompt() {
						return text;
					}
					@Override
					public boolean itemSelectable( Item item ) {
						return paths.contains( CoopItems.pathOf( localHero(), item ) );
					}
					@Override
					public void onSelect( Item item ) {
						answerItem( id, item == null ? null : CoopItems.pathOf( localHero(), item ) );
					}
				});
				break;
			case PROMPT_OPT:
				final boolean[] enabled = m.getBooleanArray( "enabled" );
				GameScene.show( new com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions( m.getString( "title" ), text, m.getStringArray( "options" ) ) {
					@Override
					protected boolean enabled( int index ) {
						return enabled == null || index >= enabled.length || enabled[index];
					}
					@Override
					protected void onSelect( int index ) {
						answerOption( id, index );
					}
					@Override
					public void onBackPressed() {
						super.onBackPressed();
						answerOption( id, -1 );
					}
				});
				break;
		}
	}

	//client: a command for the host, tagged with the snapshot it was made on
	static void sendCommand( Bundle m ){
		m.put( "seq", clientSeq );
		CoopOnline.client.send( m );
	}

	public static void answerCell( int id, int cell ){
		Bundle a = NetConnection.message( ANSWER );
		a.put( "id", id );
		a.put( "cell", cell );
		sendCommand( a );
		if (id == lastPromptId) lastPromptId = -1;
	}

	public static void answerItem( int id, String path ){
		Bundle a = NetConnection.message( ANSWER );
		a.put( "id", id );
		if (path != null) a.put( "path", path );
		sendCommand( a );
		if (id == lastPromptId) lastPromptId = -1;
	}

	public static void answerOption( int id, int index ){
		Bundle a = NetConnection.message( ANSWER );
		a.put( "id", id );
		a.put( "index", index );
		sendCommand( a );
		if (id == lastPromptId) lastPromptId = -1;
	}

	//CharSprite: on a client, finished animations must not call back into the game
	public static boolean suppressSpriteCallbacks(){
		return onlineClient();
	}

	public static void leaveGame(){
		if (Game.callbackWrapper == deferredWrapper) Game.callbackWrapper = null;
		hostInGame = false;
		clientInGame = false;
		CoopTime.online = false;
	}
}
