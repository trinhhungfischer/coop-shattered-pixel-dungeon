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
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.utils.Bundle;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Vision is not shared in coop: each hero has its own explored map (visited / mapped)
 * and the screen only shows what the active hero sees.
 *
 * Level.visited and Level.mapped always hold the arrays of one hero, the level's "owner".
 * The arrays of the other heroes are kept here, and swapped in when the active hero changes.
 * Level.heroFOV is always the active hero's own field of view.
 */
public class CoopVision implements Coop.Listener {

	private static final CoopVision INSTANCE = new CoopVision();

	static {
		Coop.addListener( INSTANCE );
	}

	public static void init(){
		//registration happens in the static initializer
	}

	private static class State {
		int owner;          //party index whose arrays are in level.visited / level.mapped
		boolean[][] visited;
		boolean[][] mapped;
	}

	private static final WeakHashMap<Level, State> states = new WeakHashMap<>();

	private static State state( Level level ){
		State s = states.get(level);
		if (s == null){
			s = new State();
			s.owner = Math.max(0, Coop.indexOf(Dungeon.hero));
			states.put(level, s);
		}
		int size = Coop.size();
		if (s.visited == null || s.visited.length < size){
			boolean[][] v = new boolean[size][];
			boolean[][] m = new boolean[size][];
			if (s.visited != null){
				System.arraycopy(s.visited, 0, v, 0, s.visited.length);
				System.arraycopy(s.mapped, 0, m, 0, s.mapped.length);
			}
			s.visited = v;
			s.mapped = m;
		}
		return s;
	}

	//makes level.visited / level.mapped belong to the active hero. Returns true if they changed.
	public static boolean sync( Level level ){
		if (!Coop.active() || level == null) return false;
		int active = Coop.indexOf(Dungeon.hero);
		if (active < 0) return false;
		State s = state(level);
		if (s.owner == active) return false;

		//park the current owner's arrays, bring in the active hero's (new heroes start unexplored)
		s.visited[s.owner] = level.visited;
		s.mapped[s.owner] = level.mapped;
		boolean[] v = s.visited[active];
		boolean[] m = s.mapped[active];
		level.visited = v != null ? v : new boolean[level.length()];
		level.mapped = m != null ? m : new boolean[level.length()];
		s.visited[active] = null;
		s.mapped[active] = null;
		s.owner = active;
		return true;
	}

	@Override
	public void onActiveHeroChanged( Hero previous, Hero current ) {
		Level level = Dungeon.level;
		if (level == null || current == null) return;
		sync( level );
		//show only the new hero's sight until its next observe
		if (current.fieldOfView != null && current.fieldOfView.length == level.heroFOV.length){
			System.arraycopy( current.fieldOfView, 0, level.heroFOV, 0, level.heroFOV.length );
		}
		GameScene.updateFog();
		GameScene.afterObserve();
		updateTeammateSprites();
	}

	@Override
	public void onPartyChanged() {
		states.clear();
		owners.clear();
	}

	// ---------------------------------------------------------------------------------
	// Summons that give vision (wards, lotuses, spirit hawks, power of many allies)
	// only share it with the hero who made them. Unknown owner = seen by everyone.
	// ---------------------------------------------------------------------------------

	//actor id -> party index of the summoner
	private static final HashMap<Integer, Integer> owners = new HashMap<>();

	public static void setOwner( Char summon, Hero owner ){
		if (!Coop.active() || summon == null || owner == null) return;
		int i = Coop.indexOf(owner);
		if (i >= 0) owners.put( summon.id(), i );
	}

	//the hero who made this summon, or fallback when unknown / solo
	public static Hero ownerOf( Char summon, Hero fallback ){
		if (!Coop.active() || summon == null) return fallback;
		Integer i = owners.get( summon.id() );
		if (i == null || i < 0 || i >= Coop.size()) return fallback;
		return Coop.get(i);
	}

	public static boolean sharesVision( Char summon, Char hero ){
		if (!Coop.active() || !(hero instanceof Hero)) return true;
		Integer i = owners.get( summon.id() );
		return i == null || i == Coop.indexOf((Hero) hero);
	}

	//true if the active hero or any other alive hero currently sees this cell
	public static boolean seenByAnyHero( Level level, int cell ){
		if (level.heroFOV[cell]) return true;
		if (!Coop.active()) return false;
		for (Hero h : Coop.aliveHeroes()){
			if (h.fieldOfView != null && h.fieldOfView.length == level.length() && h.fieldOfView[cell]){
				return true;
			}
		}
		return false;
	}

	private static final String OWNER_IDS   = "coop_summon_ids";
	private static final String OWNER_HEROES = "coop_summon_owners";

	//called from Coop.store / Coop.restore
	static void storeOwners( Bundle bundle ){
		int[] ids = new int[owners.size()];
		int[] heroes = new int[owners.size()];
		int n = 0;
		for (Map.Entry<Integer, Integer> e : owners.entrySet()){
			ids[n] = e.getKey();
			heroes[n] = e.getValue();
			n++;
		}
		bundle.put( OWNER_IDS, ids );
		bundle.put( OWNER_HEROES, heroes );
	}

	static void restoreOwners( Bundle bundle ){
		owners.clear();
		if (!bundle.contains(OWNER_IDS)) return;
		int[] ids = bundle.getIntArray( OWNER_IDS );
		int[] heroes = bundle.getIntArray( OWNER_HEROES );
		for (int i = 0; i < Math.min(ids.length, heroes.length); i++){
			owners.put( ids[i], heroes[i] );
		}
	}

	//teammates are only visible when the active hero can see them, like any other character
	public static void updateTeammateSprites(){
		if (!Coop.active() || Dungeon.level == null) return;
		for (Hero h : Coop.aliveHeroes()){
			if (h == Dungeon.hero || h.sprite == null) continue;
			if (h.pos >= 0 && h.pos < Dungeon.level.length()) {
				h.sprite.visible = Dungeon.level.heroFOV[h.pos];
			}
		}
	}

	private static final String OWNER   = "coop_vision_owner";
	private static final String VISITED = "coop_visited_";
	private static final String MAPPED  = "coop_mapped_";

	//call at the end of Level.storeInBundle. Level's own keys keep the owner's arrays.
	public static void store( Level level, Bundle bundle ){
		if (!Coop.active()) return;
		State s = states.get(level);
		if (s == null) return;
		bundle.put( OWNER, s.owner );
		for (int i = 0; i < s.visited.length; i++){
			if (i == s.owner || s.visited[i] == null) continue;
			bundle.put( VISITED + i, s.visited[i] );
			bundle.put( MAPPED + i, s.mapped[i] );
		}
	}

	//call at the end of Level.restoreFromBundle
	public static void restore( Level level, Bundle bundle ){
		if (!bundle.contains(OWNER)) return;
		State s = new State();
		s.owner = bundle.getInt( OWNER );
		int size = Math.max( s.owner + 1, 4 );
		while (bundle.contains(VISITED + size)) size++;
		s.visited = new boolean[size][];
		s.mapped = new boolean[size][];
		for (int i = 0; i < size; i++){
			if (bundle.contains(VISITED + i)) s.visited[i] = bundle.getBooleanArray( VISITED + i );
			if (bundle.contains(MAPPED + i)) s.mapped[i] = bundle.getBooleanArray( MAPPED + i );
		}
		states.put(level, s);
	}
}
