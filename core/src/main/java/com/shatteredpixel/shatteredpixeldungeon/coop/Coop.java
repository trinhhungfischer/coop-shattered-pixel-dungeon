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

package com.shatteredpixel.shatteredpixeldungeon.coop;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Central party state for coop play.
 *
 * Design rules (keep upstream merges cheap):
 *  - All coop logic lives in this package. Upstream files only get small calls into it,
 *    each marked with a "// COOP-HOOK" comment.
 *  - When coop is not active, every helper here must behave exactly like the solo game.
 *  - Dungeon.hero is kept as a pointer to the *active* hero (the one acting / shown in the UI).
 *    Use setActive() to move it, never assign Dungeon.hero directly from coop code.
 *  - The first hero in the party is the "primary" hero. It is the one saved under Dungeon's
 *    regular HERO key, so solo saves and coop saves share a format.
 *
 * Agreed gameplay rules:
 *  - Limited drops can be looted once by each hero (see CoopLoot).
 *  - Dead heroes are never revived (an Ankh still works as normal, before death happens).
 *    They become spectators; the run ends only when every hero is dead.
 */
public class Coop {

	public interface Listener {
		default void onActiveHeroChanged( Hero previous, Hero current ){}
		default void onHeroDied( Hero hero ){}
		default void onPartyChanged(){}
	}

	private static final ArrayList<Hero> party = new ArrayList<>();
	private static final ArrayList<Boolean> dead = new ArrayList<>();
	private static final ArrayList<Listener> listeners = new ArrayList<>();

	public static boolean active(){
		return party.size() > 1;
	}

	public static int size(){
		return active() ? party.size() : 1;
	}

	//starts a new coop party. The first hero is the primary hero.
	public static void start( List<Hero> heroes ){
		party.clear();
		dead.clear();
		for (Hero h : heroes){
			party.add(h);
			dead.add(false);
		}
		Dungeon.hero = party.get(0);
		for (Listener l : listeners) l.onPartyChanged();
	}

	public static void reset(){
		party.clear();
		dead.clear();
		for (Listener l : listeners) l.onPartyChanged();
	}

	//every hero in the party, including dead ones. In solo this is just Dungeon.hero.
	public static List<Hero> heroes(){
		if (!active()){
			if (Dungeon.hero == null) return Collections.emptyList();
			return Collections.singletonList(Dungeon.hero);
		}
		return Collections.unmodifiableList(party);
	}

	//heroes that are alive and not spectating.
	public static List<Hero> aliveHeroes(){
		ArrayList<Hero> result = new ArrayList<>();
		for (Hero h : heroes()){
			if (h.isAlive() && !isDead(h)) result.add(h);
		}
		return result;
	}

	public static boolean isHero( Object o ){
		return o instanceof Hero && heroes().contains(o);
	}

	public static Hero primary(){
		return active() ? party.get(0) : Dungeon.hero;
	}

	public static int indexOf( Hero h ){
		return active() ? party.indexOf(h) : (h == Dungeon.hero ? 0 : -1);
	}

	public static Hero get( int index ){
		return active() ? party.get(index) : (index == 0 ? Dungeon.hero : null);
	}

	//a dead hero is a spectator. Only meaningful in coop.
	public static boolean isDead( Hero h ){
		int i = party.indexOf(h);
		return i >= 0 && dead.get(i);
	}

	public static void markDead( Hero h ){
		int i = party.indexOf(h);
		if (i < 0 || dead.get(i)) return;
		dead.set(i, true);
		for (Listener l : listeners) l.onHeroDied(h);
	}

	//true when the run should end. In solo this is just "the hero is dead".
	public static boolean allDead(){
		return aliveHeroes().isEmpty();
	}

	//moves the Dungeon.hero pointer. Listeners (UI, quickslots...) react to the change.
	public static void setActive( Hero h ){
		Hero previous = Dungeon.hero;
		if (previous == h) return;
		Dungeon.hero = h;
		for (Listener l : listeners) l.onActiveHeroChanged(previous, h);
	}

	//hotseat: the active hero is the local one. Online play will replace this.
	public static boolean isLocal( Hero h ){
		return h == Dungeon.hero;
	}

	public static void addListener( Listener l ){
		if (!listeners.contains(l)) listeners.add(l);
	}

	public static void removeListener( Listener l ){
		listeners.remove(l);
	}

	private static final String PARTY       = "coop_party";
	private static final String PARTY_DEAD  = "coop_party_dead";
	private static final String ACTIVE      = "coop_active";

	//stores everything except the primary hero, which Dungeon saves under its HERO key.
	//Callers must make sure Dungeon.hero == primary() while Dungeon stores HERO.
	public static void store( Bundle bundle ){
		if (!active()) return;
		ArrayList<Hero> others = new ArrayList<>(party.subList(1, party.size()));
		boolean[] deadArr = new boolean[party.size()];
		for (int i = 0; i < deadArr.length; i++) deadArr[i] = dead.get(i);
		bundle.put( PARTY, others );
		bundle.put( PARTY_DEAD, deadArr );
		bundle.put( ACTIVE, Math.max(0, party.indexOf(Dungeon.hero)) );
		CoopVision.storeOwners( bundle );
	}

	//call after Dungeon.hero has been restored as the primary hero.
	public static void restore( Bundle bundle ){
		party.clear();
		dead.clear();
		if (!bundle.contains(PARTY)) {
			for (Listener l : listeners) l.onPartyChanged();
			return;
		}
		Hero primary = Dungeon.hero;
		party.add(primary);
		//Dungeon.loadGame also restores the primary hero with Dungeon.hero == null,
		//so item restore code doesn't attach anything to the wrong hero
		Dungeon.hero = null;
		for (Bundlable b : bundle.getCollection(PARTY)){
			party.add((Hero) b);
		}
		Dungeon.hero = primary;
		boolean[] deadArr = bundle.getBooleanArray(PARTY_DEAD);
		for (int i = 0; i < party.size(); i++){
			dead.add(deadArr != null && i < deadArr.length && deadArr[i]);
		}
		int act = bundle.contains(ACTIVE) ? bundle.getInt(ACTIVE) : 0;
		if (act > 0 && act < party.size()) Dungeon.hero = party.get(act);
		for (Listener l : listeners) l.onPartyChanged();
		CoopVision.restoreOwners( bundle );
	}
}
