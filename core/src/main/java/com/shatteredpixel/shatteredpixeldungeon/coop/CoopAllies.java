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
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.LifeLink;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.AuraOfProtection;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.LifeLinkSpell;
import com.watabou.utils.Bundle;

/**
 * Coop helpers for allies, summons and ally-affecting talents/spells.
 *
 * The solo code reads Dungeon.hero for "the hero who owns this effect". In coop that is the
 * active hero, which is wrong during other heroes' or mobs' turns. These helpers return the hero
 * that really owns the effect: the summoner (CoopVision owner record), the buff's carrier, or the
 * best matching hero present. Every method behaves exactly like the solo code when coop is off.
 *
 * Summons that need an owner record call CoopVision.setOwner(summon, hero) when they are created
 * (shadow clone, feint afterimage, smoke bomb decoy, ratmogrify rats, power of many, spirit hawk,
 * mirror/prismatic images). The record is keyed by actor id and saved with the party (Coop.store).
 */
public class CoopAllies {

	// *** summons ***

	//the hero who owns a summon/ally (its talents apply). ch itself if it is a party hero.
	//Solo / unknown owner: Dungeon.hero.
	public static Hero summoner( Char ch ){
		if (Coop.active() && Coop.isHero(ch)) return (Hero) ch;
		return CoopVision.ownerOf( ch, Dungeon.hero );
	}

	//true if the summon belongs to hero h. Unknown owner counts as everyone's. Always true in solo.
	public static boolean ownedBy( Char summon, Hero h ){
		if (!Coop.active()) return true;
		Hero owner = CoopVision.ownerOf( summon, null );
		return owner == null || owner == h;
	}

	//the hero an ally follows / returns to: its summoner while alive and on this level,
	//otherwise the closest alive hero. Solo: Dungeon.hero.
	public static Hero leader( Char ally ){
		if (!Coop.active()) return Dungeon.hero;
		Hero owner = CoopVision.ownerOf( ally, null );
		if (owner != null && CoopCombat.isAliveHero(owner) && CoopCombat.isOnCurrentLevel(owner)){
			return owner;
		}
		return CoopCombat.focusHero( ally.pos );
	}

	//true if ch is the leader of ally (used where solo code says "ch == Dungeon.hero")
	public static boolean isLeader( Char ally, Char ch ){
		if (!Coop.active()) return ch == Dungeon.hero;
		return ch != null && ch == leader( ally );
	}

	// *** owner records for buffs that don't store their caster ***
	// A buff keeps the caster's party index in an int field (-1 = unknown / solo) and saves it
	// with storeOwner/restoreOwner from its own bundle methods (COOP-HOOK lines).

	private static final String BUFF_OWNER = "coop_owner";

	//party index to remember for a caster, -1 in solo
	public static int ownerIndex( Hero caster ){
		return Coop.active() ? Coop.indexOf(caster) : -1;
	}

	//the caster recorded in a buff. Solo / unknown: Dungeon.hero.
	public static Hero ownerHero( int index ){
		if (!Coop.active() || index < 0 || index >= Coop.size()) return Dungeon.hero;
		return Coop.get(index);
	}

	public static void storeOwner( Bundle bundle, int index ){
		if (index >= 0) bundle.put( BUFF_OWNER, index );
	}

	public static int restoreOwner( Bundle bundle ){
		return bundle.contains(BUFF_OWNER) ? bundle.getInt(BUFF_OWNER) : -1;
	}

	// *** cleric: aura of protection ***


	//solo: is target protected by Dungeon.hero's aura (same alignment, aura active, in range or life linked)
	private static boolean auraCovers( Hero h, Char target ){
		return h.alignment == target.alignment
				&& h.buff(AuraOfProtection.AuraBuff.class) != null
				&& (Dungeon.level.distance(target.pos, h.pos) <= 2 || lifeLinkedTo(target, h));
	}

	//the hero whose aura of protection covers target, or null. The strongest aura wins in coop.
	public static Hero auraHero( Char target ){
		if (!Coop.active()){
			if (Dungeon.hero == null || Dungeon.level == null) return null;
			return auraCovers(Dungeon.hero, target) ? Dungeon.hero : null;
		}
		if (Dungeon.level == null) return null;
		Hero best = null;
		for (Hero h : CoopCombat.heroesHere()){
			if (auraCovers(h, target) && (best == null
					|| h.pointsInTalent(Talent.AURA_OF_PROTECTION) > best.pointsInTalent(Talent.AURA_OF_PROTECTION))){
				best = h;
			}
		}
		return best;
	}

	//the hero whose armor glyphs a non-hero ally borrows through aura of protection, or null.
	//Solo: original check, which excludes Dungeon.hero itself. Coop: party heroes only use their own armor.
	public static Hero auraGlyphHero( Char ch ){
		if (!Coop.active()){
			if (ch == Dungeon.hero) return null;
			return auraHero( ch );
		}
		if (Coop.isHero(ch)) return null;
		return auraHero( ch );
	}

	//solo: target has the life link spell buff. Coop: and it is linked to h (unknown link counts).
	private static boolean lifeLinkedTo( Char target, Hero h ){
		if (target.buff(LifeLinkSpell.LifeLinkSpellBuff.class) == null) return false;
		if (!Coop.active()) return true;
		boolean anyLink = false;
		for (LifeLink l : target.buffs(LifeLink.class)){
			anyLink = true;
			if (l.object == h.id()) return true;
		}
		return !anyLink;
	}

	//the hero relevant for a life link buff: the carrier if it's a hero, else the linked hero
	public static Hero lifeLinkHero( LifeLink link ){
		if (!Coop.active()) return Dungeon.hero;
		if (Coop.isHero(link.target)) return (Hero) link.target;
		Actor other = Actor.findById( link.object );
		if (Coop.isHero(other)) return (Hero) other;
		return summoner( link.target );
	}

	// *** cleric: bless talent (non-cleric heroes) ***

	//bless talent points that apply to allies' accuracy/evasion, 0 if none.
	//Solo: Dungeon.hero's points if not a cleric. Coop: the best non-cleric hero present.
	public static int blessPoints(){
		if (!Coop.active()){
			if (Dungeon.hero.heroClass != HeroClass.CLERIC && Dungeon.hero.hasTalent(Talent.BLESS)){
				return Dungeon.hero.pointsInTalent(Talent.BLESS);
			}
			return 0;
		}
		int best = 0;
		for (Hero h : CoopCombat.heroesHere()){
			if (h.heroClass != HeroClass.CLERIC){
				best = Math.max(best, h.pointsInTalent(Talent.BLESS));
			}
		}
		return best;
	}

	// *** cleric: guiding light / priest ***

	//true if the attacker is a hero with searing light (solo: attacker == Dungeon.hero && has talent)
	public static boolean searingLight( Char attacker ){
		return CoopCore.isPartyHero(attacker) && ((Hero)attacker).hasTalent(Talent.SEARING_LIGHT);
	}

	//the priest whose bonus damage triggers when attacker hits an illuminated enemy, or null.
	//Solo: Dungeon.hero if a priest and not the attacker. Coop: a priest present that isn't the attacker.
	public static Hero priestFor( Char attacker ){
		if (!Coop.active()){
			return (attacker != Dungeon.hero && Dungeon.hero.subClass == HeroSubClass.PRIEST) ? Dungeon.hero : null;
		}
		Hero owner = Coop.isHero(attacker) ? null : CoopVision.ownerOf(attacker, null);
		if (owner != null && owner.subClass == HeroSubClass.PRIEST && CoopCombat.isAliveHero(owner)) return owner;
		for (Hero h : CoopCombat.heroesHere()){
			if (h != attacker && h.subClass == HeroSubClass.PRIEST) return h;
		}
		return null;
	}
}
