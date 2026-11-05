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
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopItems;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopRemote;
import com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.Torch;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.LeatherArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfMight;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfIdentify;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.darts.Dart;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.utils.Bundle;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * netGameSim -PnetMode=actions: the client's hero goes through small actions one at a time, each
 * checked on its own on the client's mirror (which only changes through the host):
 * eating, drinking, equipping, dropping and picking up, throwing (a cell prompt), cancelling a
 * prompt, two uses sent in one turn, searching, stopping a rest and a long walk, reading a scroll
 * that teleports, an item prompt, a stale answer and a command for an item that isn't there.
 *
 * The host gives the items, keeps enemies away and plays along without leaving depth 1.
 */
public class NetActionCases {

	// ---------------------------------------------------------------------------------
	// Host
	// ---------------------------------------------------------------------------------

	static boolean gave = false;

	//render thread, game logic not running
	static void hostSetup( Hero h ){
		//no fights: they would link the heroes' turns and interrupt the client's actions
		for (Mob m : Dungeon.level.mobs.toArray( new Mob[0] )){
			if (m.alignment == Char.Alignment.ENEMY) m.die( null );
		}
		if (gave || !com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime.isWaiting( h )) return;
		gave = true;
		h.belongings.backpack.items.add( new Food().quantity( 4 ) );
		h.belongings.backpack.items.add( new PotionOfHealing().identify() );
		h.belongings.backpack.items.add( new LeatherArmor().identify() );
		h.belongings.backpack.items.add( new Torch() );
		h.belongings.backpack.items.add( new Dart().quantity( 5 ) );
		h.belongings.backpack.items.add( new ScrollOfTeleportation().identify() );
		h.belongings.backpack.items.add( new ScrollOfIdentify().identify() );
		h.belongings.backpack.items.add( new RingOfMight() );
		//hurt, so a full rest goes on until something stops it
		h.HP = Math.max( 1, h.HT / 3 );
		NetGameSim.log( "gave the client's hero food, a potion, armor, a torch, darts, scrolls and a ring; HP " + h.HP + "/" + h.HT );
	}

	// ---------------------------------------------------------------------------------
	// Client
	// ---------------------------------------------------------------------------------

	interface Prompt { void on( String type, int id ); }

	static class Case {
		final String name;
		Runnable start;
		BooleanSupplier passed;
		Prompt prompt;
		//the case needs the hero waiting for input before it starts (most do)
		boolean needsReady = true;
		long timeoutMs = 20000;
		Case( String name ){ this.name = name; }
	}

	static final List<Case> cases = new ArrayList<>();
	static int current = -1;
	static boolean started = false;
	static long startedAt = 0;
	static int passedCount = 0;
	static final List<String> failed = new ArrayList<>();
	static int lastPromptSeen = -1;

	//values the cases compare against
	static int before, before2, startPos, target, promptId, staleId = -1;
	static long mark;
	static boolean flag;

	static Hero me(){
		return CoopRemote.localHero();
	}

	static <T extends Item> T find( Class<T> cls ){
		for (Item i : CoopItems.all( me() )) if (cls.isInstance( i )) return cls.cast( i );
		return null;
	}

	static int count( Class<? extends Item> cls ){
		int n = 0;
		for (Item i : CoopItems.all( me() )) if (cls.isInstance( i )) n += i.quantity();
		return n;
	}

	static boolean ready(){
		return CoopRemote.inputReady() && CoopRemote.lastPromptId < 0;
	}

	static long since(){
		return System.currentTimeMillis() - mark;
	}

	//a free cell at a walking distance between min and max (or the farthest one below min)
	static int cellAtDistance( int min, int max ){
		Level level = Dungeon.level;
		boolean[] pass = level.passable.clone();
		//the hero only walks through cells it has seen (like in solo)
		for (int i = 0; i < pass.length; i++) if (level.avoid[i] || level.pit[i] || !(level.visited[i] || level.mapped[i])) pass[i] = false;
		PathFinder.buildDistanceMap( me().pos, pass );
		int best = -1;
		for (int c = 0; c < level.length(); c++){
			int d = PathFinder.distance[c];
			if (d == Integer.MAX_VALUE || d < 1 || Actor.findChar( c ) != null || level.heaps.get( c ) != null) continue;
			if (level.traps.get( c ) != null || level.plants.get( c ) != null) continue;
			if (d >= min && d <= max) return c;
			if (best == -1 || (d > PathFinder.distance[best] && d < min)) best = c;
		}
		return best;
	}

	static void build(){
		Case c;

		c = new Case( "eat" );
		c.start = () -> { before = count( Food.class ); CoopRemote.useItem( find( Food.class ), me(), Food.AC_EAT ); };
		c.passed = () -> count( Food.class ) == before - 1 && ready();
		cases.add( c );

		c = new Case( "two uses sent in one turn count once" );
		c.start = () -> {
			before = count( Food.class );
			Food f = find( Food.class );
			//a player tapping twice before the host answers: the second use isn't the hero's turn
			CoopRemote.useItem( f, me(), Food.AC_EAT );
			CoopRemote.useItem( f, me(), Food.AC_EAT );
			mark = System.currentTimeMillis();
		};
		c.passed = () -> count( Food.class ) < before && since() > 3000 && ready() && count( Food.class ) == before - 1;
		cases.add( c );

		c = new Case( "stop a full rest, then move" );
		c.start = () -> {
			startPos = me().pos;
			flag = false;
			CoopRemote.forwardAction( me(), CoopRemote.ACT_REST_FULL );
			mark = System.currentTimeMillis();
		};
		c.passed = () -> {
			if (!flag){
				//right away, a tap (the UI cancels the rest, like in solo): the host, without a screen to
				//draw, rests faster than any update could show it; the stop comes in right after the rest
				NetGameSim.log( "resting (HP " + me().HP + "/" + me().HT + ", ready " + me().ready + "), cancel says " + GameScene.cancel() );
				flag = true;
				return false;
			}
			if (target <= 0 && ready()){
				//stopped by the tap, not by healing up (resting online goes fast)
				if (me().HP >= me().HT){
					CoopSim.problem( "case 'stop a full rest': the hero rested until healed (HP " + me().HP + "/" + me().HT + "), the tap didn't stop it" );
				}
				target = cellAtDistance( 1, 1 );
				CoopRemote.onCellSelected( target );
				return false;
			}
			return target > 0 && me().pos == target && ready();
		};
		c.needsReady = true;
		cases.add( c );

		c = new Case( "stop a long walk" );
		c.start = () -> {
			target = cellAtDistance( 8, 40 );
			startPos = me().pos;
			flag = false;
			CoopRemote.onCellSelected( target );
			mark = System.currentTimeMillis();
		};
		c.passed = () -> {
			if (!flag){
				//a tap right away (the UI cancels a walk, like in solo): the stop comes in right after
				//the walk, which the host would otherwise finish faster than any update could show
				NetGameSim.log( "walking (at " + me().pos + ", ready " + me().ready + "), cancel says " + GameScene.cancel() );
				flag = true;
				before = me().pos;
				mark = System.currentTimeMillis();
				return false;
			}
			//stopped well before the end of the path, and waits for input again
			return since() > 3000 && ready() && me().pos != target
					&& Dungeon.level.distance( startPos, me().pos ) <= 3;
		};
		cases.add( c );

		c = new Case( "drink" );
		c.start = () -> { before = count( PotionOfHealing.class ); CoopRemote.useItem( find( PotionOfHealing.class ), me(), Potion.AC_DRINK ); };
		c.passed = () -> count( PotionOfHealing.class ) == before - 1 && ready();
		cases.add( c );

		c = new Case( "equip armor" );
		c.start = () -> CoopRemote.useItem( find( LeatherArmor.class ), me(), EquipableItem.AC_EQUIP );
		c.passed = () -> me().belongings.armor() instanceof LeatherArmor && ready();
		cases.add( c );

		c = new Case( "drop" );
		c.start = () -> CoopRemote.useItem( find( Torch.class ), me(), Item.AC_DROP );
		c.passed = () -> {
			Heap h = Dungeon.level.heaps.get( me().pos );
			return count( Torch.class ) == 0 && h != null && h.peek() instanceof Torch && ready();
		};
		cases.add( c );

		c = new Case( "pick up" );
		c.start = () -> CoopRemote.onCellSelected( me().pos );
		c.passed = () -> count( Torch.class ) == 1 && Dungeon.level.heaps.get( me().pos ) == null && ready();
		cases.add( c );

		c = new Case( "throw (cell prompt)" );
		c.start = () -> {
			before = count( Dart.class );
			before2 = CoopRemote.missilesReplayed;
			target = cellAtDistance( 2, 3 );
			CoopRemote.useItem( find( Dart.class ), me(), Item.AC_THROW );
		};
		c.prompt = ( type, id ) -> {
			if (!CoopRemote.PROMPT_CELL.equals( type )) return;
			staleId = id;
			CoopRemote.answerCell( id, target );
			GameScene.cancelCellSelector();
		};
		c.passed = () -> count( Dart.class ) == before - 1 && CoopRemote.missilesReplayed > before2 && ready();
		cases.add( c );

		c = new Case( "pick the dart back up" );
		c.start = () -> {
			before = count( Dart.class );
			//the dart lies somewhere around the target
			int dart = -1;
			for (Heap h : Dungeon.level.heaps.valueList()) if (h.peek() instanceof Dart) dart = h.pos;
			target = dart;
			if (dart != -1) CoopRemote.onCellSelected( dart );
		};
		c.passed = () -> target != -1 && count( Dart.class ) == before + 1 && ready();
		cases.add( c );

		c = new Case( "cancel a zap" );
		c.start = () -> {
			before = NetGameSim.staffCharges( find( MagesStaff.class ) );
			mark = Long.MAX_VALUE;
			CoopRemote.useItem( find( MagesStaff.class ), me(), MagesStaff.AC_ZAP );
		};
		c.prompt = ( type, id ) -> {
			if (!CoopRemote.PROMPT_CELL.equals( type )) return;
			CoopRemote.answerCell( id, -1 );
			GameScene.cancelCellSelector();
			mark = System.currentTimeMillis();
		};
		c.passed = () -> mark != Long.MAX_VALUE && since() > 2000 && ready()
				&& NetGameSim.staffCharges( find( MagesStaff.class ) ) == before;
		cases.add( c );

		c = new Case( "a stale answer changes nothing" );
		c.start = () -> {
			before = count( Dart.class );
			//the throw's prompt was answered long ago
			if (staleId >= 0) CoopRemote.answerCell( staleId, cellAtDistance( 2, 3 ) );
			mark = System.currentTimeMillis();
		};
		c.passed = () -> since() > 2000 && ready() && count( Dart.class ) == before;
		cases.add( c );

		c = new Case( "a command for an item that isn't there" );
		c.start = () -> {
			before = count( Food.class );
			Bundle m = com.shatteredpixel.shatteredpixeldungeon.coop.net.NetConnection.message( CoopRemote.USE );
			m.put( "path", "i:no.such.Item|0" );
			m.put( "cls", Food.class.getName() );
			m.put( "action", Food.AC_EAT );
			send( m );
			mark = System.currentTimeMillis();
		};
		c.passed = () -> since() > 2000 && count( Food.class ) == before;
		cases.add( c );

		c = new Case( "search" );
		c.start = () -> { flag = false; CoopRemote.forwardAction( me(), CoopRemote.ACT_SEARCH ); mark = System.currentTimeMillis(); };
		c.passed = () -> {
			if (!CoopRemote.inputReady()) flag = true;
			return since() > 1000 && ready();
		};
		cases.add( c );

		c = new Case( "teleport scroll" );
		c.start = () -> { startPos = me().pos; CoopRemote.useItem( find( ScrollOfTeleportation.class ), me(), Scroll.AC_READ ); };
		c.passed = () -> count( ScrollOfTeleportation.class ) == 0 && Dungeon.level.distance( startPos, me().pos ) > 1 && ready();
		cases.add( c );

		c = new Case( "identify (item prompt)" );
		c.start = () -> CoopRemote.useItem( find( ScrollOfIdentify.class ), me(), Scroll.AC_READ );
		c.prompt = ( type, id ) -> {
			if (!CoopRemote.PROMPT_ITEM.equals( type )) return;
			String ring = CoopItems.pathOf( me(), find( RingOfMight.class ) );
			GameScene.cancel();
			CoopRemote.answerItem( id, ring );
		};
		c.passed = () -> count( ScrollOfIdentify.class ) == 0 && find( RingOfMight.class ) != null
				&& find( RingOfMight.class ).isIdentified() && ready();
		cases.add( c );
	}

	//CoopRemote.sendCommand is package-private: commands the UI never builds
	static void send( Bundle m ){
		try {
			java.lang.reflect.Method s = CoopRemote.class.getDeclaredMethod( "sendCommand", Bundle.class );
			s.setAccessible( true );
			s.invoke( null, m );
		} catch (Exception e){
			CoopSim.problem( "can't send a raw command: " + e );
		}
	}

	static boolean itemsArrived(){
		return count( Food.class ) >= 4 && find( Dart.class ) != null && find( ScrollOfIdentify.class ) != null
				&& find( RingOfMight.class ) != null && find( Torch.class ) != null;
	}

	//every client frame in actions mode, in the game scene. Returns true once all cases are done.
	static boolean clientFrame(){
		if (cases.isEmpty()) build();
		if (current == -1){
			if (!itemsArrived() || !ready()) return false;
			NetGameSim.log( "items arrived, " + cases.size() + " cases to go" );
			current = 0;
		}
		if (current >= cases.size()) return true;
		Case c = cases.get( current );

		//prompts for this case go to it; anything else is unexpected
		int id = CoopRemote.lastPromptId;
		if (id >= 0 && id != lastPromptSeen){
			lastPromptSeen = id;
			String type = CoopRemote.lastPromptType;
			if (c.prompt != null && started){
				c.prompt.on( type, id );
			}
			if (CoopRemote.lastPromptId == id){
				NetGameSim.log( "case '" + c.name + "': unexpected prompt " + type + " '" + CoopRemote.lastPromptTitle + "', cancelling it" );
				if (CoopRemote.PROMPT_CELL.equals( type )){ CoopRemote.answerCell( id, -1 ); GameScene.cancelCellSelector(); }
				else if (CoopRemote.PROMPT_ITEM.equals( type )){ GameScene.cancel(); CoopRemote.answerItem( id, null ); }
				else { GameScene.cancel(); CoopRemote.answerOption( id, -1 ); }
			}
		}

		if (!started){
			if (c.needsReady && !ready()) return false;
			started = true;
			startedAt = System.currentTimeMillis();
			target = 0;
			c.start.run();
			return false;
		}

		boolean ok;
		try {
			ok = c.passed.getAsBoolean();
		} catch (RuntimeException e){
			CoopSim.problem( "case '" + c.name + "' crashed: " + CoopSim.stack( e ).replace( "\r", "" ).replace( "\n", " | " ) );
			ok = false;
			startedAt = 0;
		}
		if (ok){
			passedCount++;
			NetGameSim.log( "case '" + c.name + "': passed" );
			next();
		} else if (System.currentTimeMillis() - startedAt > c.timeoutMs){
			failed.add( c.name );
			CoopSim.problem( "case '" + c.name + "' failed: " + describe() );
			next();
		}
		return false;
	}

	static void next(){
		current++;
		started = false;
		NetGameSim.lastProgress = System.currentTimeMillis();
		if (current >= cases.size()){
			NetGameSim.log( "actions: " + passedCount + "/" + cases.size() + " cases passed" + (failed.isEmpty() ? "" : ", failed: " + failed) );
		}
	}

	static String describe(){
		Hero h = me();
		return "pos " + h.pos + " target " + target + " ready " + h.ready + " inputReady " + CoopRemote.inputReady()
				+ " prompt " + CoopRemote.lastPromptType + "/" + CoopRemote.lastPromptId
				+ " food " + count( Food.class ) + " darts " + count( Dart.class ) + " torch " + count( Torch.class )
				+ " healing " + count( PotionOfHealing.class ) + " armor " + (h.belongings.armor() == null ? "-" : h.belongings.armor().getClass().getSimpleName())
				+ " identify scrolls " + count( ScrollOfIdentify.class ) + " ring " + ringState()
				+ " | " + CoopRemote.clientDebug();
	}

	static String ringState(){
		RingOfMight r = find( RingOfMight.class );
		return r == null ? "-" : "identified=" + r.isIdentified() + " levelKnown=" + r.levelKnown + " cursedKnown=" + r.cursedKnown + " typeKnown=" + r.isKnown();
	}
}
