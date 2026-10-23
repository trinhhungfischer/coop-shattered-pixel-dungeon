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
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.AscensionChallenge;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.MonkEnergy;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.SwarmIntelTracker;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.ArmorAbility;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.ClericSpell;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.NPC;
import com.shatteredpixel.shatteredpixeldungeon.effects.FloatingText;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.Ring;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.VaultLevel;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Combat rules and mob AI helpers for coop.
 *
 * Every method here must behave exactly like the solo code it replaces when Coop.active() is false.
 * Upstream files (Mob, Char, Ballistica) only call into this class from lines marked "// COOP-HOOK".
 */
public class CoopCombat {

	// *** party queries ***

	//alive, non-spectating heroes that are on the level currently being played.
	//In solo this is just Dungeon.hero, if alive.
	public static List<Hero> heroesHere(){
		if (!Coop.active()){
			if (Dungeon.hero == null || !Dungeon.hero.isAlive()) return Collections.emptyList();
			return Collections.singletonList(Dungeon.hero);
		}
		ArrayList<Hero> result = new ArrayList<>();
		for (Hero h : Coop.aliveHeroes()){
			if (isOnCurrentLevel(h)) result.add(h);
		}
		return result;
	}

	//a hero is considered present if it is a registered actor and its position fits the level.
	//TODO agent A: replace with the real per-hero depth check if heroes can be on different depths
	public static boolean isOnCurrentLevel( Hero h ){
		if (Dungeon.level == null || h.pos < 0 || h.pos >= Dungeon.level.length()) return false;
		return !Coop.active() || Actor.findById(h.id()) == h;
	}

	//true if ch is an alive party hero that is not a spectator
	public static boolean isAliveHero( Object ch ){
		return Coop.isHero(ch) && ((Hero)ch).isAlive() && !Coop.isDead((Hero)ch);
	}

	public static boolean anyHeroAlive(){
		if (!Coop.active()) return Dungeon.hero.isAlive();
		return !Coop.allDead();
	}

	public static boolean anyHeroHas( Class<? extends Buff> buff ){
		if (!Coop.active()) return Dungeon.hero.buff(buff) != null;
		for (Hero h : heroesHere()){
			if (h.buff(buff) != null) return true;
		}
		return false;
	}

	//ch itself if it is a party hero, otherwise the active hero.
	//Used where the solo code says Dungeon.hero but really means "the hero involved".
	public static Hero heroOf( Char ch ){
		if (Coop.active() && Coop.isHero(ch)) return (Hero) ch;
		return Dungeon.hero;
	}

	//the alive party hero closest to a cell, or null
	public static Hero closestHero( int cell ){
		Hero best = null;
		int bestDist = Integer.MAX_VALUE;
		for (Hero h : heroesHere()){
			int d = Dungeon.level.distance(cell, h.pos);
			if (d < bestDist){
				best = h;
				bestDist = d;
			}
		}
		return best;
	}

	//the hero we assume is responsible for indirect damage (wands, spells, armor abilities).
	//In solo this is Dungeon.hero. In coop it's the active hero, or the closest alive hero if the
	//active hero is not around anymore (e.g. died, or it's a mob turn after their death).
	public static Hero attributedHero( Mob mob ){
		if (!Coop.active()) return Dungeon.hero;
		if (isAliveHero(Dungeon.hero) && isOnCurrentLevel(Dungeon.hero)) return Dungeon.hero;
		Hero h = closestHero(mob.pos);
		return h != null ? h : Dungeon.hero;
	}

	//the hero a mob "thinks about" when it has no specific target (wander toward the hero, etc.).
	//Solo: Dungeon.hero. Coop: closest alive hero to cell, falling back to Dungeon.hero.
	public static Hero focusHero( int cell ){
		if (!Coop.active()) return Dungeon.hero;
		Hero h = closestHero(cell);
		return h != null ? h : Dungeon.hero;
	}

	// *** targeting ***

	//Mob.chooseEnemy: a hostile mob hunting a hero it can no longer see switches to another hero it can see.
	//Always false in solo.
	public static boolean shouldRetarget( Mob mob, Char enemy, boolean[] fieldOfView ){
		if (!Coop.active() || enemy == null || mob.alignment != Char.Alignment.ENEMY) return false;
		if (!Coop.isHero(enemy) || fieldOfView == null || fieldOfView.length != Dungeon.level.length()) return false;
		if (isAliveHero(enemy) && fieldOfView[enemy.pos] && enemy.invisible <= 0) return false;
		for (Hero h : heroesHere()){
			if (h != enemy && fieldOfView[h.pos] && h.invisible <= 0) return true;
		}
		return false;
	}

	//adds every alive party hero the mob can see. Solo: same check as the original code on Dungeon.hero.
	public static void addVisibleHeroes( Collection<Char> enemies, boolean[] fieldOfView ){
		if (!Coop.active()){
			if (fieldOfView[Dungeon.hero.pos] && Dungeon.hero.invisible <= 0) {
				enemies.add(Dungeon.hero);
			}
			return;
		}
		for (Hero h : heroesHere()){
			if (fieldOfView[h.pos] && h.invisible <= 0){
				enemies.add(h);
			}
		}
	}

	//tie-break rule from Mob.chooseEnemy.
	//Solo keeps the original (slightly odd) precedence: (curr is hero && same dist) || both attackable.
	//Coop: between two heroes, the one that last damaged the mob wins ties. Heroes still beat non-heroes on ties.
	public static boolean preferAsTarget( Mob mob, Char curr, Char closest, boolean sameDist, boolean bothAttackable ){
		if (!Coop.active()){
			return (curr == Dungeon.hero && sameDist) || bothAttackable;
		}
		boolean currHero = Coop.isHero(curr);
		boolean closestHero = Coop.isHero(closest);
		if (currHero && closestHero){
			if (!sameDist && !bothAttackable) return false;
			if (curr.id() == mob.coopLastHeroID) return true;
			if (closest.id() == mob.coopLastHeroID) return false;
			//stable fallback so HashSet order doesn't matter: lower party index
			return Coop.indexOf((Hero)curr) < Coop.indexOf((Hero)closest);
		}
		return (currHero && sameDist) || bothAttackable;
	}

	//remembers the last party hero that damaged a mob, for target tie-breaking
	public static void recordDamage( Mob mob, Object src ){
		if (!Coop.active()) return;
		if (Coop.isHero(src)){
			mob.coopLastHeroID = ((Hero)src).id();
		} else if (src instanceof Wand || src instanceof ClericSpell || src instanceof ArmorAbility){
			mob.coopLastHeroID = attributedHero(mob).id();
		}
	}

	//each hero gets their own swarm intelligence indicator
	public static void trackSwarmIntel(){
		if (!Coop.active()){
			Buff.affect( Dungeon.hero, SwarmIntelTracker.class );
			return;
		}
		for (Hero h : heroesHere()){
			Buff.affect( h, SwarmIntelTracker.class );
		}
	}

	//replaces "Dungeon.hero.heroClass == HeroClass.CLERIC" for the guiding light illumination check.
	//attacker is the char attacking the illuminated mob.
	public static boolean illuminatedByCleric( Char attacker ){
		if (!Coop.active()) return Dungeon.hero.heroClass == HeroClass.CLERIC;
		if (Coop.isHero(attacker)){
			return ((Hero)attacker).heroClass == HeroClass.CLERIC;
		}
		for (Hero h : heroesHere()){
			if (h.heroClass == HeroClass.CLERIC) return true;
		}
		return false;
	}

	//the hero that benefits from soul mark. attacker may be null.
	//Solo: Dungeon.hero. Coop: the attacker if it is a warlock hero, otherwise the first warlock present.
	public static Hero soulMarkOwner( Char attacker ){
		if (!Coop.active()) return Dungeon.hero;
		if (Coop.isHero(attacker) && ((Hero)attacker).subClass == HeroSubClass.WARLOCK){
			return (Hero)attacker;
		}
		for (Hero h : heroesHere()){
			if (h.subClass == HeroSubClass.WARLOCK) return h;
		}
		return Dungeon.hero;
	}

	// *** friendly fire ***

	//party heroes never hurt each other with direct attacks (melee, thrown weapons).
	//AoE (bombs, fire, gas, lightning...) uses damage() directly and is not affected.
	public static boolean isTeammateAttack( Char attacker, Char defender ){
		return Coop.active() && attacker != defender
				&& Coop.isHero(attacker) && Coop.isHero(defender);
	}

	//Ballistica: true if the shooter (char at sourcePos) is a party hero
	public static boolean shooterIsHero( int sourcePos ){
		if (!Coop.active()) return false;
		return Coop.isHero(Actor.findChar(sourcePos));
	}

	//Ballistica: projectiles fired by a hero pass through other alive party heroes,
	//unless that hero is exactly the aimed-at cell (so targeting a teammate on purpose still works)
	public static boolean passesThrough( int sourcePos, int cell, int targetPos ){
		if (cell == targetPos || cell == sourcePos) return false;
		Char ch = Actor.findChar(cell);
		return isAliveHero(ch);
	}

	// *** EXP and kills ***

	//coop replacement for the exp part of Mob.destroy: every alive hero on the level gets the full amount,
	//each with its own level cap check.
	public static void awardKillExp( Mob mob ){
		for (Hero h : heroesHere()){
			int exp = h.lvl <= mob.maxLvl ? mob.EXP : 0;

			if (h.buff(AscensionChallenge.class) != null &&
					exp == 0 && mob.maxLvl > 0 && mob.EXP > 0 && h.lvl < Hero.MAX_LEVEL){
				exp = Math.round(10 * mob.spawningWeight());
			}

			if (exp > 0 && h.sprite != null) {
				h.sprite.showStatusWithIcon(CharSprite.POSITIVE, Integer.toString(exp), FloatingText.EXPERIENCE);
			}
			h.earnExp(exp, mob.getClass());

			if (exp == 0 && Dungeon.level instanceof VaultLevel){
				Item ring = h.belongings.ring();
				if (ring != null){
					ring.onHeroGainExp(0.2f, h);
				}
				Item misc = h.belongings.misc();
				if (misc instanceof Ring){
					misc.onHeroGainExp(0.2f, h);
				}
			}

			if (h.subClass == HeroSubClass.MONK){
				Buff.affect(h, MonkEnergy.class).gainEnergy(mob);
			}
		}
	}

	//the hero that gets on-kill talent effects (lethal momentum / lethal haste), or null.
	//Solo: Dungeon.hero when cause is the hero, a weapon or an enchantment (original condition).
	public static Hero meleeKiller( Object cause ){
		if (!Coop.active()){
			if (cause == Dungeon.hero || cause instanceof Weapon || cause instanceof Weapon.Enchantment){
				return Dungeon.hero;
			}
			return null;
		}
		if (Coop.isHero(cause)) return (Hero) cause;
		//weapons/enchantments are only used as causes during the wielder's own turn
		if (cause instanceof Weapon || cause instanceof Weapon.Enchantment) return Dungeon.hero;
		return null;
	}

	//per-hero record of what killed them, for agent A's death/rankings handling
	private static final HashMap<Hero, Object> deathCauses = new HashMap<>();

	public static Object deathCause( Hero h ){
		return deathCauses.get(h);
	}

	public static void clearDeathCauses(){
		deathCauses.clear();
	}

	//call when a party hero was killed by cause. Replaces a direct Dungeon.fail(cause) call.
	//Solo: exactly Dungeon.fail(cause). Coop: the run only fails once every hero is dead.
	public static void heroKilled( Hero victim, Object cause ){
		if (!Coop.active()){
			Dungeon.fail(cause);
			return;
		}
		deathCauses.put(victim, cause);
		if (Coop.allDead()){
			Dungeon.fail(cause);
		}
	}

	//kill message for a hero death caused by a char
	public static String killMessage( Hero victim, Char killer ){
		if (Coop.active()){
			String s = Messages.get(CoopCombat.class, "kill", killer.name(), victim.name());
			if (!s.equals(Messages.NO_TEXT_FOUND)) return Messages.capitalize(s);
		}
		return Messages.capitalize(Messages.get(Char.class, "kill", killer.name()));
	}

	// *** balance ***

	public static float hpMultiplier(){
		return 1f + 0.5f * (Coop.size() - 1);
	}

	//scales a newly added hostile mob's HP by party size. Applied once per mob (flag is saved in the mob bundle).
	public static void scaleNewMob( Mob mob ){
		if (!Coop.active() || mob.coopHpScaled) return;
		if (mob.alignment == Char.Alignment.ALLY || mob instanceof NPC) return;
		if (mob.HT <= 0) return;
		float percent = mob.HP / (float) mob.HT;
		mob.HT = Math.round(mob.HT * hpMultiplier());
		mob.HP = Math.max(1, Math.round(mob.HT * percent));
		mob.coopHpScaled = true;
	}

	// *** allies following the party between levels ***

	//heroes whose surroundings decide which intelligent allies travel along. null = every hero on the level.
	private static List<Hero> travellers = null;

	//Agent A: call before Mob.holdAllies(level) when only part of the party changes level,
	//and call with null afterwards (or rely on restoreAllies clearing it).
	public static void setTravellers( List<Hero> heroes ){
		travellers = heroes == null ? null : new ArrayList<>(heroes);
	}

	//Mob.holdAllies distance check. Solo: distance(holdFromPos, allyPos) <= 5 as in the original.
	//Coop: also counts allies near any travelling hero.
	public static boolean inHoldRange( Level level, int holdFromPos, int allyPos ){
		if (level.distance(holdFromPos, allyPos) <= 5) return true;
		if (!Coop.active()) return false;
		List<Hero> heroes = travellers != null ? travellers : heroesHere();
		for (Hero h : heroes){
			if (h.isAlive() && h.pos >= 0 && h.pos < level.length()
					&& level.distance(h.pos, allyPos) <= 5){
				return true;
			}
		}
		return false;
	}

	//default hold position for Mob.holdAllies(level)
	public static int holdPos(){
		if (!Coop.active()) return Dungeon.hero.pos;
		List<Hero> heroes = travellers != null ? travellers : heroesHere();
		return heroes.isEmpty() ? Dungeon.hero.pos : heroes.get(0).pos;
	}

	//Mob.restoreAllies: don't drop allies onto cells already taken by party heroes (coop only)
	public static boolean cellTakenByHero( int cell ){
		if (!Coop.active()) return false;
		for (Hero h : Coop.aliveHeroes()){
			if (h.pos == cell) return true;
		}
		return false;
	}

	public static void onAlliesRestored(){
		travellers = null;
	}

	//logs a kill message for a hero, used by Char.attack
	public static void logHeroKilled( Hero victim, Char killer ){
		GLog.n( killMessage(victim, killer) );
	}
}
