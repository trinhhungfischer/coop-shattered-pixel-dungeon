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
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.FlavourBuff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Light;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.LockedFloor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.LloydsBeacon;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfForce;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.mechanics.Ballistica;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Rect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

/**
 * Boss fight helpers for coop.
 *
 * Every method returns exactly what the solo code it replaces would compute when Coop.active() is false.
 * Upstream boss / boss level files only call into this class from lines marked "// COOP-HOOK".
 */
public class CoopBosses {

	// *** who is "the hero" for a boss ***

	//solo: c == Dungeon.hero. Coop: c is any party hero.
	public static boolean isHero( Object c ){
		return CoopCore.isPartyHero( c );
	}

	//the hero a boss ability aims at, given the boss's current enemy and position.
	//Solo: Dungeon.hero. Coop: the enemy if it is an alive hero, otherwise the closest alive hero.
	public static Hero target( Char enemy, int fromPos ){
		if (!Coop.active()) return Dungeon.hero;
		if (CoopCombat.isAliveHero(enemy) && CoopCombat.isOnCurrentLevel((Hero) enemy)) return (Hero) enemy;
		return CoopCombat.focusHero( fromPos );
	}

	//the hero a boss "senses" when it has no enemy (beckon / default target).
	//Solo: Dungeon.hero. Coop: closest alive hero that isn't invisible, else the closest alive hero.
	public static Hero seekHero( int fromPos ){
		if (!Coop.active()) return Dungeon.hero;
		Hero best = null;
		int bestDist = Integer.MAX_VALUE;
		for (Hero h : CoopCombat.heroesHere()){
			if (h.invisible > 0) continue;
			int d = Dungeon.level.distance(fromPos, h.pos);
			if (d < bestDist){
				best = h;
				bestDist = d;
			}
		}
		return best != null ? best : CoopCombat.focusHero( fromPos );
	}

	//Crystal spire target: it always tracks the hero, even an invisible one.
	//Solo: Dungeon.hero. Coop: closest alive hero the spire can see, else the closest alive hero.
	public static Hero spireTarget( boolean[] fieldOfView, int fromPos ){
		if (!Coop.active()) return Dungeon.hero;
		Hero best = null;
		int bestDist = Integer.MAX_VALUE;
		for (Hero h : CoopCombat.heroesHere()){
			if (fieldOfView == null || fieldOfView.length != Dungeon.level.length() || !fieldOfView[h.pos]) continue;
			int d = Dungeon.level.distance(fromPos, h.pos);
			if (d < bestDist){
				best = h;
				bestDist = d;
			}
		}
		return best != null ? best : CoopCombat.focusHero( fromPos );
	}

	// *** distances to the party ***

	//solo: level.trueDistance(cell, Dungeon.hero.pos). Coop: distance to the nearest alive hero.
	public static float partyTrueDistance( Level level, int cell ){
		if (!Coop.active()) return level.trueDistance(cell, Dungeon.hero.pos);
		float best = Float.MAX_VALUE;
		for (Hero h : CoopCombat.heroesHere()){
			best = Math.min(best, level.trueDistance(cell, h.pos));
		}
		return best == Float.MAX_VALUE ? level.trueDistance(cell, Dungeon.hero.pos) : best;
	}

	//solo: level.trueDistance(cell, Dungeon.hero.pos) <= dist. Coop: true for any alive hero.
	public static boolean nearParty( Level level, int cell, float dist ){
		return partyTrueDistance( level, cell ) <= dist;
	}

	//Tengu arena jump: solo rejects cells closer than 5 or further than 7 from the hero.
	//Coop: rejects cells closer than 5 to any alive hero (the 5-7 window still applies to Tengu's enemy).
	public static boolean badArenaJump( Level level, int cell ){
		if (!Coop.active()){
			return level.distance(cell, Dungeon.hero.pos) < 5 || level.distance(cell, Dungeon.hero.pos) > 7;
		}
		for (Hero h : CoopCombat.heroesHere()){
			if (level.distance(cell, h.pos) < 5) return true;
		}
		return false;
	}

	//the hero Tengu's cell traps are generated around: must stand inside the 7x7 interior.
	//Solo: Dungeon.hero. Coop: Tengu's enemy if inside, else the closest alive hero inside, else Dungeon.hero.
	public static Hero trapHero( Level level, Rect interior, Char enemy, int tenguPos ){
		if (!Coop.active()) return Dungeon.hero;
		if (CoopCombat.isAliveHero(enemy) && interior.inside(level.cellToPoint(enemy.pos))) return (Hero) enemy;
		Hero best = null;
		int bestDist = Integer.MAX_VALUE;
		for (Hero h : CoopCombat.heroesHere()){
			if (!interior.inside(level.cellToPoint(h.pos))) continue;
			int d = level.distance(tenguPos, h.pos);
			if (d < bestDist){
				best = h;
				bestDist = d;
			}
		}
		return best != null ? best : Dungeon.hero;
	}

	// *** party wide effects ***

	//solo: Dungeon.hero.interrupt(). Coop: every alive hero on the level.
	public static void interruptAll(){
		if (!Coop.active()){
			Dungeon.hero.interrupt();
			return;
		}
		for (Hero h : CoopCombat.heroesHere()){
			h.interrupt();
		}
	}

	//solo: Buff.prolong(Dungeon.hero, cls, duration). Coop: every alive hero on the level.
	public static void prolongOnParty( Class<? extends FlavourBuff> cls, float duration ){
		if (!Coop.active()){
			Buff.prolong( Dungeon.hero, cls, duration );
			return;
		}
		for (Hero h : CoopCombat.heroesHere()){
			Buff.prolong( h, cls, duration );
		}
	}

	//solo: detaches cls from Dungeon.hero if present. Coop: from every hero.
	public static void detachFromParty( Class<? extends Buff> cls ){
		for (Hero h : Coop.heroes()){
			Buff b = h.buff(cls);
			if (b != null) b.detach();
		}
	}

	//solo: Dungeon.hero.buffs(cls). Coop: the buffs of every hero.
	public static <T extends Buff> ArrayList<T> partyBuffs( Class<T> cls ){
		ArrayList<T> result = new ArrayList<>();
		for (Hero h : Coop.heroes()){
			result.addAll( h.buffs(cls) );
		}
		return result;
	}

	//solo: upgrades Dungeon.hero's Lloyd's beacon, if any. Coop: every alive hero's beacon.
	public static void upgradeBeacons(){
		List<Hero> heroes = Coop.active() ? Coop.aliveHeroes() : Coop.heroes();
		for (Hero h : heroes){
			LloydsBeacon beacon = h.belongings.getItem(LloydsBeacon.class);
			if (beacon != null) {
				beacon.upgrade();
			}
		}
	}

	//whether Tengu drops his mask. Solo: if the hero has no subclass.
	//Coop: if any alive hero has none; that one mask can then be looted once by every hero.
	public static int masksToDrop(){
		if (!Coop.active()) return Dungeon.hero.subClass == HeroSubClass.NONE ? 1 : 0;
		int n = 0;
		for (Hero h : Coop.aliveHeroes()){
			if (h.subClass == HeroSubClass.NONE) n++;
		}
		return n;
	}

	//Dwarf King badge check: solo is "src == Dungeon.hero && hero isn't fighting unarmed".
	public static boolean heroHitArmed( Object src ){
		if (!isHero(src)) return false;
		Hero h = (Hero) src;
		return !RingOfForce.fightingUnarmed(h) || h.buff(RingOfForce.Force.class) != null;
	}

	//solo: the hero's view distance follows the level's unless it has Light. Coop: the other heroes too.
	//Call after the solo code updated Dungeon.hero.
	public static void syncViewDistance( Level level ){
		if (!Coop.active()) return;
		for (Hero h : Coop.aliveHeroes()){
			if (h != Dungeon.hero && h.buff(Light.class) == null){
				h.viewDistance = level.viewDistance;
			}
		}
	}

	//Yog phase 0: solo checks Dungeon.hero.viewDistance >= distance(pos, hero.pos).
	public static boolean anyHeroInViewRange( int pos ){
		if (!Coop.active()) return Dungeon.hero.viewDistance >= Dungeon.level.distance(pos, Dungeon.hero.pos);
		for (Hero h : CoopCombat.heroesHere()){
			if (h.viewDistance >= Dungeon.level.distance(pos, h.pos)) return true;
		}
		return false;
	}

	//solo: Dungeon.level.heroFOV[cell]. Coop: seen by any alive hero.
	public static boolean seen( int cell ){
		return CoopVision.seenByAnyHero( Dungeon.level, cell );
	}

	//Yog holds its beams while "the hero" is rooted. Solo: Dungeon.hero.rooted.
	//Coop: while any rooted alive hero stands on one of the beam paths.
	public static boolean yogHoldsFire( int yogPos, Collection<Integer> targetedCells ){
		if (!Coop.active()) return Dungeon.hero.rooted;
		HashSet<Integer> rootedCells = new HashSet<>();
		for (Hero h : CoopCombat.heroesHere()){
			if (h.rooted) rootedCells.add(h.pos);
		}
		if (rootedCells.isEmpty()) return false;
		for (int cell : targetedCells){
			for (int p : new Ballistica(yogPos, cell, Ballistica.WONT_STOP).path){
				if (rootedCells.contains(p)) return true;
			}
		}
		return false;
	}

	// *** locked floor: every hero's timer moves together ***

	private static boolean sharingLock = false;

	//called from LockedFloor.addTime: applies the same time to the other heroes' locks
	public static void onLockAddTime( LockedFloor lock, float time ){
		if (!Coop.active() || sharingLock) return;
		sharingLock = true;
		try {
			for (Hero h : Coop.aliveHeroes()){
				if (h == lock.target) continue;
				LockedFloor other = h.buff(LockedFloor.class);
				if (other != null) other.addTime(time);
			}
		} finally {
			sharingLock = false;
		}
	}

	//called from LockedFloor.removeTime
	public static void onLockRemoveTime( LockedFloor lock, float time ){
		if (!Coop.active() || sharingLock) return;
		sharingLock = true;
		try {
			for (Hero h : Coop.aliveHeroes()){
				if (h == lock.target) continue;
				LockedFloor other = h.buff(LockedFloor.class);
				if (other != null) other.removeTime(time);
			}
		} finally {
			sharingLock = false;
		}
	}

	// *** arena triggers ***

	private static Hero trigger = null;

	//remember which hero stepped on an arena trigger. Call from Level.occupyCell before progress()/seal().
	public static void triggeredBy( Char ch ){
		if (Coop.active() && ch instanceof Hero && Coop.isHero(ch)) trigger = (Hero) ch;
	}

	//the hero scripted boss events happen around. Solo: Dungeon.hero.
	//Coop: the hero that last triggered an arena, if still alive here, otherwise the active hero.
	public static Hero anchor(){
		if (!Coop.active()) return Dungeon.hero;
		if (trigger != null && CoopCombat.isAliveHero(trigger) && CoopCombat.isOnCurrentLevel(trigger)) return trigger;
		return Dungeon.hero;
	}

	/**
	 * Puts the other alive heroes next to the anchor hero, on free cells inside area (null = anywhere).
	 * With moveAll every other hero is moved, otherwise only those that are outside area or on an
	 * invalid cell. reserved cells are never used (e.g. where a boss is about to appear).
	 * Requires the level's flag maps (passable etc.) to be up to date. No-op in solo.
	 */
	public static void gatherParty( Level level, Hero anchor, Rect area, boolean moveAll, int... reserved ){
		if (!Coop.active() || anchor == null || level == null) return;

		HashSet<Integer> taken = new HashSet<>();
		taken.add(anchor.pos);
		for (int r : reserved) taken.add(r);

		ArrayList<Hero> toMove = new ArrayList<>();
		for (Hero h : CoopCombat.heroesHere()){
			if (h == anchor) continue;
			if (!moveAll && freeCell(level, h.pos, area, taken, h)){
				taken.add(h.pos);
			} else {
				toMove.add(h);
			}
		}
		if (toMove.isEmpty()) return;

		//candidates in walking distance order from the anchor, so nobody lands behind a wall
		PathFinder.buildDistanceMap( anchor.pos, level.passable );
		int[] dist = PathFinder.distance.clone();

		for (Hero h : toMove){
			int cell = -1;
			for (int pass = 0; pass < 2 && cell == -1; pass++){
				int bestDist = Integer.MAX_VALUE;
				for (int c = 0; c < level.length(); c++){
					if (dist[c] >= bestDist || dist[c] == Integer.MAX_VALUE) continue;
					if (pass == 0 && level.traps.get(c) != null) continue;
					if (freeCell(level, c, area, taken, h)){
						cell = c;
						bestDist = dist[c];
					}
				}
			}
			if (cell == -1) continue; //nowhere to go, leave the hero where it is

			taken.add(cell);
			placeHero( h, cell );
		}

		Dungeon.observe();
	}

	private static boolean freeCell( Level level, int cell, Rect area, HashSet<Integer> taken, Hero mover ){
		if (cell < 0 || cell >= level.length() || !level.insideMap(cell)) return false;
		if (!level.passable[cell] || level.solid[cell] || level.avoid[cell] || level.invalidHeroPos(cell)) return false;
		if (area != null && !area.inside(level.cellToPoint(cell))) return false;
		if (taken.contains(cell)) return false;
		Char ch = Actor.findChar(cell);
		return ch == null || ch == mover;
	}

	//coop only: takes the intelligent allies near any hero off the level before a scripted map change
	//that would destroy them (solo keeps its original behaviour, which only spares allies near the hero)
	public static void holdAllies( Level level ){
		if (!Coop.active()) return;
		Mob.holdAllies( level );
	}

	//coop only: puts allies taken by holdAllies back around pos
	public static void restoreAllies( Level level, int pos ){
		if (!Coop.active()) return;
		Mob.restoreAllies( level, pos );
	}

	//moves a hero to a cell without triggering anything, and cancels whatever it was doing
	public static void placeHero( Hero h, int cell ){
		h.interrupt();
		h.curAction = h.lastAction = null;
		h.pos = cell;
		if (h.sprite != null){
			h.sprite.interruptMotion();
			h.sprite.place(cell);
		}
	}
}
