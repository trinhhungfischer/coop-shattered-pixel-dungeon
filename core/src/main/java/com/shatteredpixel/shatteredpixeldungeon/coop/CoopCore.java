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
import com.shatteredpixel.shatteredpixeldungeon.QuickSlot;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.AscensionChallenge;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Awareness;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Light;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.LockedFloor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.MagicalSight;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.MindVision;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.RevealedArea;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.cleric.PowerOfMany;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.huntress.SpiritHawk;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.DivineSense;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.TalismanOfForesight;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.TimekeepersHourglass;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfChallenge;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfRegrowth;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfWarding;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.plants.Swiftthistle;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndResurrect;
import com.watabou.utils.BArray;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Party core for coop: party creation, turn order, shared FOV, level travel and death.
 * Every method is a no-op (or returns the solo answer) when Coop is not active.
 */
public class CoopCore {

	//max distance (in tiles) from the stairs that every alive hero must be within to change floors:
	//1 means everyone stands on or right next to the stairs
	public static final int TRANSITION_RANGE = 1;

	// ---------------------------------------------------------------------------------
	// New run
	// ---------------------------------------------------------------------------------

	//call at the end of Dungeon.init(), after the primary hero got its class kit.
	public static void initParty(){
		fovs.clear();
		keepPositions = false;

		//drop a party left over from an earlier coop run (Coop.primary() etc. must not see it)
		if (Coop.active()) Coop.reset();

		if (!CoopSetup.pending()){
			return;
		}

		Hero primary = Dungeon.hero;

		ArrayList<Hero> party = new ArrayList<>();
		party.add(primary);
		for (HeroClass cls : CoopSetup.extraClasses){
			Hero h = new Hero();
			h.live();
			Dungeon.hero = primary;
			//gives the extra hero its own QuickSlot for its starting kit
			CoopQuickslots.beginHeroInit( h );
			//party is not started yet: Item.collect() puts the class kit into Dungeon.hero's
			//backpack, so the pointer must be on the hero being created.
			Dungeon.hero = h;
			cls.initHero( h );
			party.add(h);
		}
		Dungeon.hero = primary;
		CoopQuickslots.endHeroInit();

		Coop.start(party);
		CoopSetup.clear();
	}

	// ---------------------------------------------------------------------------------
	// Actors / turns
	// ---------------------------------------------------------------------------------

	//adds every alive party hero (Dungeon.hero is added by Actor.init itself)
	public static void addPartyActors(){
		if (!Coop.active()) return;
		for (Hero h : Coop.aliveHeroes()){
			Actor.add( h );
		}
	}

	//true when actor processing should stop because no hero can act anymore
	public static boolean runStopped(){
		if (!Coop.active()){
			return Dungeon.hero == null || !Dungeon.hero.isAlive();
		}
		//a pending ankh resurrection pauses the game, like in solo
		return Coop.allDead() || WndResurrect.instance != null;
	}

	//called at the start of Hero.act(): hotseat pointer follows the acting hero
	public static void onHeroAct( Hero h ){
		if (!Coop.active()) return;
		Coop.setActive( h );
		ensureOwnFOV( h );
		//the screen shows this hero's own sight: when it gets the turn back while already waiting
		//for input, Hero.act doesn't observe again, so its last view must be shown here
		Level level = Dungeon.level;
		if (level != null && h.fieldOfView != level.heroFOV && h.fieldOfView.length == level.heroFOV.length){
			System.arraycopy( h.fieldOfView, 0, level.heroFOV, 0, h.fieldOfView.length );
			GameScene.updateFog();
			GameScene.afterObserve();
			CoopVision.updateTeammateSprites();
		}
		CoopTime.onHeroAct( h );
	}

	//called after a resting hero recomputed only its own FOV: keep the screen's FOV in sync
	public static void afterRestFOV( Hero h ){
		if (!Coop.active() || h != Dungeon.hero || Dungeon.level == null) return;
		if (h.fieldOfView != Dungeon.level.heroFOV && h.fieldOfView.length == Dungeon.level.heroFOV.length){
			System.arraycopy( h.fieldOfView, 0, Dungeon.level.heroFOV, 0, h.fieldOfView.length );
		}
		CoopVision.updateTeammateSprites();
	}

	//solo: "c is the hero". Coop: c is any party hero.
	public static boolean isPartyHero( Object c ){
		if (!Coop.active()) return c == Dungeon.hero;
		return c instanceof Hero && Coop.isHero(c);
	}

	//moves the active pointer to a hero if code that follows only works on Dungeon.hero
	public static void focus( Char c ){
		if (Coop.active() && c instanceof Hero && Coop.isHero(c) && !Coop.isDead((Hero)c)){
			Coop.setActive( (Hero)c );
		}
	}

	// ---------------------------------------------------------------------------------
	// Field of view
	// ---------------------------------------------------------------------------------

	private static final IdentityHashMap<Hero, boolean[]> fovs = new IdentityHashMap<>();

	//in coop each hero gets its own FOV array, Level.heroFOV becomes the union of them
	private static void ensureOwnFOV( Hero h ){
		Level level = Dungeon.level;
		if (level == null) return;
		boolean[] arr = fovs.get(h);
		if (arr == null || arr.length != level.length()){
			arr = new boolean[level.length()];
			fovs.put(h, arr);
			h.fieldOfView = arr;
			level.updateFieldOfView( h, arr );
		}
		h.fieldOfView = arr;
	}

	private static int defaultObserveDist( Hero h ){
		int dist = Math.max(h.viewDistance, 8);
		dist *= 1f + 0.25f*h.pointsInTalent(Talent.FARSIGHT);
		if (h.buff(MagicalSight.class) != null){
			dist = Math.max( dist, MagicalSight.DISTANCE );
		}
		return dist+1;
	}

	//coop version of Dungeon.observe(int). Returns false if the solo code should run instead.
	//Vision is not shared: only the active hero's sight and explored map are updated and shown
	//(see CoopVision). The other heroes update their own when they act.
	public static boolean observe( int dist ){
		if (!Coop.active()) return false;
		Level level = Dungeon.level;
		if (level == null) return true;

		Hero active = Dungeon.hero;
		if (active == null || !active.isAlive() || Coop.isDead(active)
				|| active.pos < 0 || active.pos >= level.length()) {
			return false;
		}
		List<Hero> alive = new ArrayList<>();
		alive.add(active);

		CoopVision.sync( level );

		ensureOwnFOV( active );
		level.updateFieldOfView( active, active.fieldOfView );
		System.arraycopy( active.fieldOfView, 0, level.heroFOV, 0, level.heroFOV.length );

		for (Hero h : alive){
			int hd = Math.max( dist, defaultObserveDist(h) );
			revealBox( level, h.pos, hd );

			//always visit adjacent tiles, even if they aren't seen
			for (int i : PathFinder.NEIGHBOURS9){
				level.visited[h.pos+i] = true;
			}
		}

		for (Hero h : alive){
			if (h.buff(MindVision.class) != null || h.buff(DivineSense.DivineSenseTracker.class) != null){
				for (Mob m : level.mobs.toArray(new Mob[0])){
					if (m instanceof Mimic && m.alignment == Char.Alignment.NEUTRAL && ((Mimic) m).stealthy()){
						continue;
					}
					if (Char.hasProp(m, Char.Property.OBJECT)){
						continue;
					}
					revealAround( level, m.pos );
				}
			}

			if (h.buff(Awareness.class) != null){
				for (Heap heap : level.heaps.valueList()){
					revealAround( level, heap.pos );
				}
			}

			for (TalismanOfForesight.CharAwareness c : h.buffs(TalismanOfForesight.CharAwareness.class)){
				Char ch = (Char) Actor.findById(c.charID);
				if (ch == null || !ch.isAlive() || Char.hasProp(ch, Char.Property.OBJECT)) continue;
				revealAround( level, ch.pos );
			}

			for (TalismanOfForesight.HeapAwareness ha : h.buffs(TalismanOfForesight.HeapAwareness.class)){
				if (Dungeon.depth != ha.depth || Dungeon.branch != ha.branch) continue;
				revealAround( level, ha.pos );
			}

			for (RevealedArea a : h.buffs(RevealedArea.class)){
				if (Dungeon.depth != a.depth || Dungeon.branch != a.branch) continue;
				revealAround( level, a.pos );
			}
		}

		for (Char ch : Actor.chars()){
			if ((ch instanceof WandOfWarding.Ward
					|| ch instanceof WandOfRegrowth.Lotus
					|| ch instanceof SpiritHawk.HawkAlly
					|| ch.buff(PowerOfMany.PowerBuff.class) != null)
					&& CoopVision.sharesVision( ch, active )){
				revealBox( level, ch.pos, ch.viewDistance+1 );
			}
		}

		GameScene.afterObserve();
		CoopVision.updateTeammateSprites();
		return true;
	}

	private static void revealBox( Level level, int center, int dist ){
		int x = center % level.width();
		int y = center / level.width();

		//left, right, top, bottom
		int l = Math.max( 0, x - dist );
		int r = Math.min( x + dist, level.width() - 1 );
		int t = Math.max( 0, y - dist );
		int b = Math.min( y + dist, level.height() - 1 );

		int width = r - l + 1;
		int height = b - t + 1;

		int pos = l + t * level.width();
		for (int i = t; i <= b; i++) {
			BArray.or( level.visited, level.heroFOV, pos, width, level.visited );
			pos += level.width();
		}

		GameScene.updateFog( l, t, width, height );
	}

	private static void revealAround( Level level, int cell ){
		BArray.or( level.visited, level.heroFOV, cell - 1 - level.width(), 3, level.visited );
		BArray.or( level.visited, level.heroFOV, cell - 1, 3, level.visited );
		BArray.or( level.visited, level.heroFOV, cell - 1 + level.width(), 3, level.visited );
		//updates adjacent cells too
		GameScene.updateFog( cell, 2 );
	}

	// ---------------------------------------------------------------------------------
	// Save / load
	// ---------------------------------------------------------------------------------

	//the hero Dungeon stores under its HERO key. Coop.store() saves the others + active index.
	public static Hero heroToSave(){
		return Coop.primary();
	}

	//true right after a game load: switchLevel keeps the saved positions of the other heroes
	private static boolean keepPositions = false;

	public static void onGameLoaded(){
		fovs.clear();
		keepPositions = Coop.active();
	}

	// ---------------------------------------------------------------------------------
	// Level travel
	// ---------------------------------------------------------------------------------

	private static boolean sameLevel = false;

	//call in Dungeon.switchLevel before Dungeon.level is replaced
	public static void beforeSwitchLevel( Level level ){
		sameLevel = Coop.active() && Dungeon.level == level;
	}

	//places the other alive heroes after Dungeon.hero got its position on the new level.
	//After a load, or when staying on the same level (ankh resurrection) heroes keep their
	//positions when still valid. Otherwise they arrive on free cells around pos.
	public static void placeParty( Level level, int pos ){
		boolean keep = keepPositions || sameLevel;
		keepPositions = false;
		sameLevel = false;
		if (!Coop.active()) return;

		fovs.clear();

		HashSet<Integer> taken = new HashSet<>();
		taken.add(pos);
		for (Hero h : Coop.aliveHeroes()){
			if (h == Dungeon.hero) continue;

			if (keep && validArrivalCell( level, h.pos, taken )){
				taken.add(h.pos);
			} else {
				h.pos = freeCellNear( level, pos, taken );
				taken.add(h.pos);
			}

			if (h.buff(AscensionChallenge.class) != null){
				h.buff(AscensionChallenge.class).onLevelSwitch();
			}

			Light light = h.buff( Light.class );
			h.viewDistance = light == null ? level.viewDistance : Math.max( Light.DISTANCE, level.viewDistance );

			h.curAction = h.lastAction = null;
		}
	}

	private static boolean validArrivalCell( Level level, int cell, HashSet<Integer> taken ){
		return cell >= 0 && cell < level.length()
				&& level.insideMap(cell)
				&& level.passable[cell]
				&& !level.avoid[cell]
				&& !level.invalidHeroPos(cell)
				&& !taken.contains(cell)
				&& level.findMob(cell) == null;
	}

	private static int freeCellNear( Level level, int pos, HashSet<Integer> taken ){
		//adjacent cells first, preferring ones without traps
		for (int pass = 0; pass < 2; pass++){
			for (int i : PathFinder.NEIGHBOURS8){
				int c = pos + i;
				if (validArrivalCell(level, c, taken) && (pass == 1 || level.traps.get(c) == null)){
					return c;
				}
			}
		}
		//then growing rings
		int w = level.width();
		int px = pos % w, py = pos / w;
		for (int r = 2; r < Math.max(w, level.height()); r++){
			for (int y = py - r; y <= py + r; y++){
				for (int x = px - r; x <= px + r; x++){
					if (Math.max(Math.abs(x - px), Math.abs(y - py)) != r) continue;
					if (x < 0 || y < 0 || x >= w || y >= level.height()) continue;
					int c = x + y*w;
					if (validArrivalCell(level, c, taken) && level.traps.get(c) == null){
						return c;
					}
				}
			}
		}
		return pos;
	}

	//call in switchLevel after Actor.init(): pushes mobs off cells occupied by other heroes
	public static void displaceMobsFromParty( Level level ){
		if (!Coop.active()) return;
		//every hero sees the new level right away: a field of view left over from the previous
		//level has the wrong size and would make turn groups read cells of the old map
		for (Hero h : Coop.aliveHeroes()){
			ensureOwnFOV( h );
			level.updateFieldOfView( h, h.fieldOfView );
		}
		for (Hero h : Coop.aliveHeroes()){
			if (h == Dungeon.hero) continue;
			for (Mob m : level.mobs){
				if (m.pos == h.pos && !Char.hasProp(m, Char.Property.IMMOVABLE)){
					for (int i : PathFinder.NEIGHBOURS8){
						if (Actor.findChar(m.pos+i) == null && level.passable[m.pos + i]){
							m.pos += i;
							break;
						}
					}
				}
			}
		}
	}

	//stairs rule: every alive hero must stand next to the stairs (within TRANSITION_RANGE),
	//on every floor including boss floors. Then the whole party travels together.
	public static boolean canPartyTransition( Hero h, LevelTransition t ){
		if (!Coop.active() || t == null) return true;
		Level level = Dungeon.level;
		List<Hero> party = Coop.aliveHeroes();

		int range = transitionRange( level, t );

		for (Hero o : party){
			if (o == h) continue;
			if (distanceTo( level, t, o.pos ) > range){
				GLog.w( Messages.get(CoopCore.class, "party_not_gathered") );
				return false;
			}
		}
		return true;
	}

	//how far from the stairs every hero has to be to change floors. Stairs in a corner or a narrow
	//corridor may not have room for the whole party next to them: then heroes queued one tile
	//further back count too, so the party can never get stuck.
	public static int transitionRange( Level level, LevelTransition t ){
		int range = TRANSITION_RANGE;
		if (standableCellsNear( level, t, range ) < Coop.aliveHeroes().size()) range++;
		return range;
	}

	//a hero right next to stairs that a teammate stands on can take them too (instead of swapping)
	public static boolean nextToTeammateOnStairs( Hero h, LevelTransition t ){
		if (!Coop.active() || t == null) return false;
		Level level = Dungeon.level;
		for (Hero o : Coop.aliveHeroes()){
			if (o != h && t.inside(o.pos) && level.adjacent(h.pos, o.pos)) return true;
		}
		return false;
	}

	//distance in tiles from a cell to the transition area (0 = inside it)
	private static int distanceTo( Level level, LevelTransition t, int cell ){
		int x = cell % level.width();
		int y = cell / level.width();
		int dx = Math.max( 0, Math.max( t.left - x, x - t.right ) );
		int dy = Math.max( 0, Math.max( t.top - y, y - t.bottom ) );
		return Math.max( dx, dy );
	}

	//how many heroes can stand within range of the transition area
	private static int standableCellsNear( Level level, LevelTransition t, int range ){
		int count = 0;
		for (int y = t.top - range; y <= t.bottom + range; y++){
			for (int x = t.left - range; x <= t.right + range; x++){
				if (x < 0 || y < 0 || x >= level.width() || y >= level.height()) continue;
				int cell = x + y * level.width();
				if (level.passable[cell] && !level.solid[cell]) count++;
			}
		}
		return count;
	}

	//coop part of Level.beforeTransition(): same per-hero cleanup for the other heroes
	public static void beforeTransition(){
		if (!Coop.active()) return;
		for (Hero h : Coop.aliveHeroes()){
			if (h == Dungeon.hero) continue;

			TimekeepersHourglass.timeFreeze timeFreeze = h.buff(TimekeepersHourglass.timeFreeze.class);
			if (timeFreeze != null) timeFreeze.disarmPresses();
			Swiftthistle.TimeBubble timeBubble = h.buff(Swiftthistle.TimeBubble.class);
			if (timeBubble != null) timeBubble.disarmPresses();

			Talent.WarriorFoodImmunity foodImmune = h.buff(Talent.WarriorFoodImmunity.class);
			if (foodImmune != null) foodImmune.detach();
			ScrollOfChallenge.ChallengeArena arena = h.buff(ScrollOfChallenge.ChallengeArena.class);
			if (arena != null) arena.detach();
			Awareness awareness = h.buff(Awareness.class);
			if (awareness != null) awareness.detach();

			h.spendToWhole();
		}
	}

	//locked (boss) floors affect every alive hero
	public static void onSeal(){
		if (!Coop.active()) return;
		for (Hero h : Coop.aliveHeroes()){
			if (h != Dungeon.hero) Buff.affect(h, LockedFloor.class);
		}
	}

	public static void onUnseal(){
		if (!Coop.active()) return;
		for (Hero h : Coop.heroes()){
			if (h != Dungeon.hero && h.buff(LockedFloor.class) != null){
				h.buff(LockedFloor.class).detach();
			}
		}
	}

	//mob respawn distance: distance to the nearest alive hero, stored in PathFinder.distance
	public static void buildPartyDistanceMap( boolean[] passable ){
		PathFinder.buildDistanceMap( Dungeon.hero.pos, passable );
		if (!Coop.active()) return;

		int[] min = PathFinder.distance.clone();
		for (Hero h : Coop.aliveHeroes()){
			if (h == Dungeon.hero) continue;
			PathFinder.buildDistanceMap( h.pos, passable );
			for (int i = 0; i < min.length; i++){
				if (PathFinder.distance[i] < min[i]) min[i] = PathFinder.distance[i];
			}
		}
		System.arraycopy( min, 0, PathFinder.distance, 0, min.length );
	}

	// ---------------------------------------------------------------------------------
	// Death
	// ---------------------------------------------------------------------------------

	//called in Hero.die() after super.die(). Returns true if the hero became a spectator
	//(other heroes remain alive), false if the normal game over flow must run.
	public static boolean spectate( Hero h, Object cause ){
		if (!Coop.active()) return false;

		Hero next = null;
		for (Hero o : Coop.aliveHeroes()){
			if (o != h){
				next = o;
				break;
			}
		}

		if (next == null){
			//last hero: reallyDie() works on Dungeon.hero
			Coop.setActive( h );
			return false;
		}

		dropBackpack( h );

		//Char.die() only removes the hero itself from the actor list, not its buffs
		for (Buff b : h.buffs()){
			Actor.remove( b );
		}
		Actor.remove( h );
		Actor.releaseIfCurrent( h );
		fovs.remove( h );

		Coop.markDead( h );
		if (Dungeon.hero == h || !Dungeon.hero.isAlive() || Coop.isDead(Dungeon.hero)){
			Coop.setActive( next );
		}

		Dungeon.observe();
		return true;
	}

	//same approach as Hero.reallyDie(), but every backpack item is dropped and removed
	//from the spectator, so it is not saved twice.
	private static void dropBackpack( Hero h ){
		Level level = Dungeon.level;
		int pos = h.pos;

		ArrayList<Integer> cells = new ArrayList<>();
		for (int ofs : PathFinder.NEIGHBOURS8){
			int cell = pos + ofs;
			if ((level.passable[cell] || level.avoid[cell]) && level.heaps.get( cell ) == null){
				cells.add( cell );
			}
		}
		Collections.shuffle( cells );

		ArrayList<Item> items = new ArrayList<>(h.belongings.backpack.items);
		int i = 0;
		QuickSlot slots = CoopQuickslots.of( h );
		for (Item item : items){
			h.belongings.backpack.items.remove( item );
			if (slots != null) slots.clearItem( item );
			int cell = cells.isEmpty() ? pos : cells.get( i++ % cells.size() );
			Heap heap = level.drop( item, cell );
			if (heap != null && heap.sprite != null) heap.sprite.drop( pos );
		}
	}
}
