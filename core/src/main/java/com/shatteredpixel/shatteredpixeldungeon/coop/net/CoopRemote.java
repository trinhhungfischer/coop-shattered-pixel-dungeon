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
			CoopOnline.client.send( m );
			Hero me = localHero();
			if (me != null) me.ready = false; //until the host says we wait again
			return true;
		}
		if (onlineHost()){
			commands.add( new int[]{ 0, cell, ACT_CELL } );
			return true;
		}
		return false;
	}

	private static volatile boolean applyingCommand = false;

	//Hero.rest / Hero.search from the toolbar: online they become commands. Returns true if forwarded.
	public static boolean forwardAction( Hero hero, int kind ){
		if (applyingCommand || hero != localHero()) return false;
		if (onlineClient()){
			Bundle m = NetConnection.message( ACT );
			m.put( "kind", kind );
			CoopOnline.client.send( m );
			hero.ready = false;
			return true;
		}
		if (onlineHost()){
			commands.add( new int[]{ 0, -1, kind } );
			return true;
		}
		return false;
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
	private static final ConcurrentLinkedQueue<int[]> commands = new ConcurrentLinkedQueue<>();
	private static final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();

	private static volatile boolean snapshotNeeded = true;
	private static Level sentLevel = null;
	private static int seq = 0;
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
		commands.clear();
		events.clear();
		CoopTime.online = true;
		CoopOnline.host.setEvents( new CoopHost.Events() {
			@Override
			public void onGameMessage( int slot, Bundle message ) {
				int idx = partyIndexOfSlot( slot );
				if (idx < 0) return;
				switch (NetConnection.type( message )){
					case CELL:
						commands.add( new int[]{ idx, message.getInt( "cell" ), ACT_CELL } );
						break;
					case ACT:
						commands.add( new int[]{ idx, -1, message.getInt( "kind" ) } );
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
		});
	}

	//Actor.process, at the top of every scheduling pass (actor thread, nothing is acting)
	public static void beforeScan(){
		if (!onlineHost() || Dungeon.level == null || Dungeon.hero == null) return;

		applyCommands();

		if (Dungeon.level != sentLevel) snapshotNeeded = true;
		long now = System.currentTimeMillis();
		if (snapshotNeeded){
			sendSnapshot();
		} else if (now - lastUpdate >= UPDATE_MS){
			sendUpdate();
		}
	}

	private static void applyCommands(){
		int[] c;
		boolean any = false;
		while ((c = commands.poll()) != null){
			if (c[0] < 0 || c[0] >= Coop.size()) continue;
			Hero h = Coop.get( c[0] );
			if (!h.isAlive() || Coop.isDead( h ) || !CoopTime.isWaiting( h )) continue;
			Coop.setActive( h );
			applyingCommand = true;
			try {
				switch (c[2]){
					case ACT_CELL:      h.handle( c[1] ); break;
					case ACT_REST:      h.rest( false ); break;
					case ACT_REST_FULL: h.rest( true ); break;
					case ACT_SEARCH:    h.search( true ); break;
				}
			} finally {
				applyingCommand = false;
			}
			any = true;
		}
		if (any) Coop.setActive( localHero() );
	}

	private static void sendSnapshot(){
		snapshotNeeded = false;
		try {
			Dungeon.saveAll();
		} catch (IOException e){
			ShatteredPixelDungeon.reportException( e );
			return;
		}
		int slot = GamesInProgress.curSlot;
		byte[] game = FileUtils.getFileHandle( GamesInProgress.gameFile( slot ) ).readBytes();
		byte[] level = FileUtils.getFileHandle( GamesInProgress.depthFile( slot, Dungeon.depth, Dungeon.branch ) ).readBytes();

		Bundle m = NetConnection.message( SNAP );
		m.put( "seq", ++seq );
		m.put( "game", Base64.getEncoder().encodeToString( game ) );
		m.put( "level", Base64.getEncoder().encodeToString( level ) );
		m.put( "depth", Dungeon.depth );
		m.put( "branch", Dungeon.branch );
		m.put( "party", slotsByPartyIndex );
		CoopOnline.host.broadcast( m );

		sentLevel = Dungeon.level;
		sentIds.clear();
		for (Char ch : Actor.chars()) sentIds.add( ch.id() );
		mapHash = Arrays.hashCode( Dungeon.level.map );
		heapHash = heapsBundle().toString().hashCode();
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

	public static void startClient(){
		clientInGame = false;
		clientSeq = -1;
		incoming.clear();
		CoopOnline.client.setEvents( new CoopClient.Events() {
			@Override
			public void onGameMessage( Bundle message ) {
				incoming.add( message );
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
				incoming.poll();
				loadSnapshot( m );
				//updates for this snapshot are applied once its scene is up
				return;
			}
			if (type.equals( "lost" )){
				incoming.poll();
				clientInGame = false;
				CoopOnline.leave();
				ShatteredPixelDungeon.scene().addToFront( new WndMessage( Messages.get( CoopRemote.class, "lost" ) ) );
				return;
			}
			if (!(Game.scene() instanceof GameScene) || Dungeon.level == null || !clientInGame) return;
			incoming.poll();
			if (type.equals( UPD ) && m.getInt( "seq" ) == clientSeq){
				applyUpdate( m );
			}
		}
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
		resyncAsked = false;
		InterlevelScene.mode = InterlevelScene.Mode.CONTINUE;
		Game.switchScene( InterlevelScene.class );
	}

	//CoopCore.onGameLoaded: the client plays its own hero
	public static void afterGameLoaded(){
		if (!onlineClient()) return;
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
			if (fresh != null) copyHero( fresh, mirror );
		}

		boolean[] waiting = m.getBooleanArray( "waiting" );
		if (waiting != null){
			for (int i = 0; i < Math.min( waiting.length, Coop.size() ); i++){
				Coop.get( i ).ready = waiting[i];
			}
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
		Bundle b = new Bundle();
		from.belongings.storeInBundle( b );
		to.belongings.restoreFromBundle( b );
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
			Actor a = Actor.findById( Integer.parseInt( p[1] ) );
			if (!(a instanceof Char) || ((Char) a).sprite == null) return;
			Char ch = (Char) a;
			switch (p[0]){
				case "atk": case "zap": case "op":
					ch.sprite.coopPlay( p[0], Integer.parseInt( p[2] ) );
					break;
				case "st":  ch.sprite.showStatus( Integer.parseInt( p[2] ), p.length > 3 ? p[3] : "" ); break;
			}
		} catch (RuntimeException ignored){
			//a malformed or stale event only loses an animation
		}
	}

	//CharSprite: on a client, finished animations must not call back into the game
	public static boolean suppressSpriteCallbacks(){
		return onlineClient();
	}

	public static void leaveGame(){
		hostInGame = false;
		clientInGame = false;
		CoopTime.online = false;
	}
}
