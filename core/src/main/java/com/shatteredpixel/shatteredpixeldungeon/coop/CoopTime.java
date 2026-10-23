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
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Hunger;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Turn groups in the style of Divinity: Original Sin 2 (see docs/coop-online-design.md, section 4).
 *
 * Heroes that affect each other (close together, drawn into the same fight, facing an awake
 * boss, or in a sealed boss arena) form one group and take turns in a shared order, like plain hotseat. Heroes that don't
 * are in separate groups with their own clocks: a group whose hero waits for input is paused,
 * together with the mobs and buffs that belong to it, so the other groups can play on without
 * waiting for it. When groups join up again, the clock of the group that is behind is moved up.
 *
 * In hotseat one hero has the "focus": the hero the player controls. The game waits for the
 * focus hero's input like in solo; other heroes that are waiting for input simply pause their
 * group. Tapping a teammate of another group hands the focus to them.
 *
 * Everything here runs on the actor thread, except requestFocus (render thread), which only
 * hands the focus over while the game is waiting for input.
 */
public class CoopTime implements Coop.Listener {

	private static final CoopTime INSTANCE = new CoopTime();

	static {
		Coop.addListener( INSTANCE );
	}

	public static void init(){
		//registration happens in the static initializer
	}

	//heroes this close (in tiles) share a group
	public static final int LINK_RANGE = 8;

	//the hero the player controls in hotseat
	private static volatile Hero focus = null;

	//group id per party index, recomputed whenever a hero is about to act
	private static int[] groupOf = new int[0];

	//actor -> hero whose group it follows (absent: not decided yet, null value: level-wide actor)
	private static final IdentityHashMap<Actor, Hero> owners = new IdentityHashMap<>();

	//more than one living hero: groups matter. With one hero left the game is plain solo.
	public static boolean enabled(){
		return Coop.active() && Coop.aliveHeroes().size() > 1;
	}

	public static Hero focus(){
		return focus;
	}

	@Override
	public void onPartyChanged() {
		focus = null;
		groupOf = new int[0];
		owners.clear();
	}

	@Override
	public void onHeroDied( Hero hero ) {
		if (focus == hero) focus = null;
		owners.clear();
	}

	// ---------------------------------------------------------------------------------
	// Groups
	// ---------------------------------------------------------------------------------

	//a hero waiting for its player's input
	public static boolean isWaiting( Hero h ){
		return h.ready && h.curAction == null && !h.resting;
	}

	private static int group( Hero h ){
		int i = Coop.indexOf( h );
		return i >= 0 && i < groupOf.length ? groupOf[i] : -1;
	}

	public static boolean sameGroup( Hero a, Hero b ){
		if (a == null || b == null) return false;
		int ga = group(a);
		return ga != -1 && ga == group(b);
	}

	//recomputes which heroes share a group. Returns the groups as lists of heroes.
	private static List<List<Hero>> computeGroups(){
		List<Hero> alive = Coop.aliveHeroes();
		Level level = Dungeon.level;
		int n = Coop.size();
		int[] parent = new int[n];
		for (int i = 0; i < n; i++) parent[i] = i;

		for (int a = 0; a < alive.size(); a++){
			for (int b = a+1; b < alive.size(); b++){
				Hero ha = alive.get(a), hb = alive.get(b);
				if (linked( level, ha, hb )){
					union( parent, Coop.indexOf(ha), Coop.indexOf(hb) );
				}
			}
		}

		//fights, like combat in DOS2: every hero drawn into a fight with the same enemy takes turns
		//together with the others in it, however far apart they stand. An awake boss draws in the
		//whole party (boss aggro covers the floor).
		if (level != null){
			for (Mob m : level.mobs){
				if (!m.isAlive() || m.alignment != Char.Alignment.ENEMY || m.state != m.HUNTING) continue;
				Hero first = null;
				for (Hero h : alive){
					if (Char.hasProp( m, Char.Property.BOSS ) || engaged( h, m )){
						if (first == null) first = h;
						else union( parent, Coop.indexOf(first), Coop.indexOf(h) );
					}
				}
			}
		}

		int[] groups = new int[n];
		List<List<Hero>> result = new ArrayList<>();
		for (int i = 0; i < n; i++) groups[i] = -1;
		for (Hero h : alive){
			int root = find( parent, Coop.indexOf(h) );
			if (groups[root] == -1){
				groups[root] = result.size();
				result.add( new ArrayList<>() );
			}
			groups[Coop.indexOf(h)] = groups[root];
			result.get( groups[root] ).add( h );
		}
		groupOf = groups;
		return result;
	}

	private static boolean linked( Level level, Hero a, Hero b ){
		if (level == null) return true;
		//sealed boss arenas: everyone fights together
		if (level.locked) return true;
		return level.distance( a.pos, b.pos ) <= LINK_RANGE;
	}

	//a hero is in the fight with a hunting enemy if the enemy is after them, they can see it,
	//or they were the last hero to hit it
	private static boolean engaged( Hero h, Mob m ){
		return m.isTargeting( h ) || sees( h, m.pos ) || m.coopLastHeroID == h.id();
	}

	private static boolean sees( Hero h, int cell ){
		return h.fieldOfView != null && cell >= 0 && cell < h.fieldOfView.length && h.fieldOfView[cell];
	}

	private static int find( int[] parent, int i ){
		while (parent[i] != i) i = parent[i] = parent[parent[i]];
		return i;
	}

	private static void union( int[] parent, int a, int b ){
		parent[find(parent, a)] = find(parent, b);
	}

	//the hero whose group an actor follows, or null for level-wide actors (blobs, timers...)
	private static Hero ownerOf( Actor actor ){
		if (actor instanceof Hero) return (Hero) actor;
		if (actor instanceof Buff){
			Char target = ((Buff) actor).target;
			return target == null ? null : ownerOf( target );
		}
		if (!(actor instanceof Char)) return null;
		if (owners.containsKey( actor )) return owners.get( actor );
		Hero owner = pickOwner( (Char) actor );
		owners.put( actor, owner );
		return owner;
	}

	private static Hero pickOwner( Char ch ){
		List<Hero> alive = Coop.aliveHeroes();
		if (alive.isEmpty()) return null;
		//a mob fighting a hero belongs with that hero
		if (ch instanceof Mob && ((Mob) ch).state == ((Mob) ch).HUNTING){
			for (Hero h : alive){
				if (((Mob) ch).isTargeting( h )) return h;
			}
		}
		//allies stay with whoever summoned them
		Hero summoner = CoopVision.ownerOf( ch, null );
		if (summoner != null && alive.contains( summoner )) return summoner;
		//everything else goes with the closest hero
		Hero best = null;
		for (Hero h : alive){
			if (best == null || Dungeon.level.distance( ch.pos, h.pos ) < Dungeon.level.distance( ch.pos, best.pos )){
				best = h;
			}
		}
		return best;
	}

	//the clock of a group: where its heroes are in time
	private static float groupNow( List<Hero> group ){
		float t = -Float.MAX_VALUE;
		for (Hero h : group) t = Math.max( t, Actor.coopTime( h ) );
		return t;
	}

	// ---------------------------------------------------------------------------------
	// Hooks
	// ---------------------------------------------------------------------------------

	//Actor.process: true if this actor's group is paused, so it must not act now
	public static boolean skip( Actor actor ){
		if (!enabled()) return false;
		Hero owner = ownerOf( actor );
		if (owner == null) return false;
		int g = group( owner );
		if (g == -1) return false;
		//a group is paused while one of its heroes waits for input and isn't the controlled hero
		for (Hero h : Coop.aliveHeroes()){
			if (h != focus && group(h) == g && isWaiting( h )){
				if (sameGroup( h, focus )){
					//groups joined up: inside one group it's simply this hero's turn, so it gets the controls
					focus = h;
					continue;
				}
				return true;
			}
		}
		return false;
	}

	//start of Hero.act: regroup, line up the clocks of groups that joined, reassign mobs
	public static void onHeroAct( Hero acting ){
		if (!enabled()){
			owners.clear();
			return;
		}
		if (focus == null || !focus.isAlive() || Coop.isDead( focus )) focus = acting;

		int[] before = groupOf.clone();
		IdentityHashMap<Actor, Hero> oldOwners = new IdentityHashMap<>( owners );
		List<List<Hero>> groups = computeGroups();

		for (List<Hero> group : groups){
			alignJoinedGroups( group, before );
		}

		//mobs may have moved to another hero's group: a mob that was paused must not get a burst
		//of catch-up turns, so it starts at the clock of its new group
		owners.clear();
		for (Actor a : Actor.all()){
			if (!(a instanceof Char) || a instanceof Hero) continue;
			Hero newOwner = ownerOf( a );
			Hero oldOwner = oldOwners.get( a );
			if (newOwner != null && oldOwner != null && !sameGroup( newOwner, oldOwner )){
				float now = Actor.coopTime( newOwner );
				float t = Actor.coopTime( a );
				if (t < now) shiftWithBuffs( (Char) a, now - t );
			}
		}
	}

	//heroes of one new group that came from different old groups: move the late ones' clocks up
	private static void alignJoinedGroups( List<Hero> group, int[] before ){
		if (group.size() < 2) return;
		ArrayList<Integer> oldIds = new ArrayList<>();
		for (Hero h : group){
			int i = Coop.indexOf( h );
			int old = i < before.length ? before[i] : -1;
			if (!oldIds.contains( old )) oldIds.add( old );
		}
		if (oldIds.size() < 2) return;

		float lead = groupNow( group );
		for (int old : oldIds){
			List<Hero> part = new ArrayList<>();
			for (Hero h : group){
				int i = Coop.indexOf( h );
				if ((i < before.length ? before[i] : -1) == old) part.add( h );
			}
			float lag = lead - groupNow( part );
			if (lag <= 0) continue;

			for (Actor a : Actor.all()){
				Hero owner = (a instanceof Char || a instanceof Buff) ? ownerBefore( a ) : null;
				if (owner != null && part.contains( owner )){
					Actor.coopShift( a, lag );
				}
			}
			//the time that passed for the others also passed for them
			for (Hero h : part){
				Hunger hunger = h.buff( Hunger.class );
				if (hunger != null) hunger.affectHunger( -lag );
			}
		}
	}

	//owner as cached before regrouping (heroes and buffs are always resolved directly)
	private static Hero ownerBefore( Actor a ){
		if (a instanceof Hero) return (Hero) a;
		if (a instanceof Buff){
			Char target = ((Buff) a).target;
			return target == null ? null : ownerBefore( target );
		}
		return owners.get( a );
	}

	private static void shiftWithBuffs( Char ch, float delta ){
		Actor.coopShift( ch, delta );
		for (Buff b : ch.buffs()){
			Actor.coopShift( b, delta );
		}
	}

	/**
	 * Actor.process, after a hero's act() returned false. A hero that now waits for input either
	 * takes the focus (its group is the one being played) or steps aside so the other groups can
	 * go on. Returns true if the actor loop should continue instead of waiting for input.
	 */
	public static boolean afterHeroWaits( Actor actor ){
		if (!enabled() || !(actor instanceof Hero)) return false;
		Hero h = (Hero) actor;
		if (!isWaiting( h )) return false;

		Hero f = focus;
		if (f == null || f == h || !f.isAlive() || Coop.isDead( f ) || !isWaiting( f ) || sameGroup( f, h )){
			focus = h;
			return false;
		}
		//another group waits for its own player: let the controlled hero's side keep playing
		Actor.releaseIfCurrent( h );
		Coop.setActive( f );
		return true;
	}

	//hotseat: hand the controls to a hero of another group (render thread, while waiting for input)
	public static boolean requestFocus( Hero h ){
		if (!enabled() || h == null || !h.isAlive() || Coop.isDead( h )) return false;
		Hero old = focus;
		if (h == old || sameGroup( old, h )) return false;
		focus = h;
		if (old != null) Actor.releaseIfCurrent( old );
		return true;
	}
}
