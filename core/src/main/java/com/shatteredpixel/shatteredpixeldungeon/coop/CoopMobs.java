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
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.QuickSlot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.Ratmogrify;


/**
 * Small helpers for non-boss mob logic that the solo code writes against Dungeon.hero.
 * Every method behaves exactly like the solo code when coop is off.
 */
public class CoopMobs {

	//thieves / crystal mimics escaping. Solo: !heroFOV[pos] && distance(Dungeon.hero.pos, pos) >= dist.
	//Coop: no alive hero sees pos and every alive hero on the level is at least dist away.
	public static boolean escapedFromHeroes( int pos, int dist ){
		if (!Coop.active()){
			return !Dungeon.level.heroFOV[pos] && Dungeon.level.distance(Dungeon.hero.pos, pos) >= dist;
		}
		if (CoopVision.seenByAnyHero(Dungeon.level, pos)) return false;
		for (Hero h : CoopCombat.heroesHere()){
			if (Dungeon.level.distance(h.pos, pos) < dist) return false;
		}
		return true;
	}

	//true if no alive hero sees the cell. Solo: !Dungeon.level.heroFOV[cell]
	public static boolean unseen( int cell ){
		return !CoopVision.seenByAnyHero(Dungeon.level, cell);
	}

	//removes an item that is being stolen from a hero from that hero's quickslots.
	//Solo: Dungeon.quickslot.convertToPlaceholder(item)
	public static void convertStolenToPlaceholder( Hero victim, Item item ){
		QuickSlot q = Coop.active() ? CoopQuickslots.of(victim) : Dungeon.quickslot;
		if (q == null) q = Dungeon.quickslot;
		q.convertToPlaceholder(item);
	}

	//the hero to interrupt for something that affects ch. Solo: Dungeon.hero.
	//Coop: ch if it is a party hero, otherwise the closest alive hero (who'd notice it).
	public static Hero heroToInterrupt( Char ch ){
		if (!Coop.active()) return Dungeon.hero;
		if (Coop.isHero(ch)) return (Hero) ch;
		return CoopCombat.focusHero(ch.pos);
	}

	//rats turn neutral when seen by a hero with ratmogrify.
	//Solo: Dungeon.level.heroFOV[cell] && Dungeon.hero.armorAbility instanceof Ratmogrify
	public static boolean seenByRatmogrifier( int cell ){
		if (!Coop.active()){
			return Dungeon.level.heroFOV[cell] && Dungeon.hero.armorAbility instanceof Ratmogrify;
		}
		for (Hero h : CoopCombat.heroesHere()){
			if (h.armorAbility instanceof Ratmogrify && h.fieldOfView != null
					&& h.fieldOfView.length == Dungeon.level.length() && h.fieldOfView[cell]){
				return true;
			}
		}
		return false;
	}

	//true if any alive hero sees the cell. Solo: Dungeon.level.heroFOV[cell]

	public static boolean seen( int cell ){
		return CoopVision.seenByAnyHero(Dungeon.level, cell);
	}

	//interrupts every alive hero that can see one of the cells, and the active hero.
	//Solo: Dungeon.hero.interrupt() (callers already checked that the hero sees it)
	public static void interruptWatchers( int... cells ){
		Dungeon.hero.interrupt();
		if (!Coop.active()) return;
		for (Hero h : CoopCombat.heroesHere()){
			if (h == Dungeon.hero || h.fieldOfView == null || h.fieldOfView.length != Dungeon.level.length()) continue;
			for (int cell : cells){
				if (cell >= 0 && cell < h.fieldOfView.length && h.fieldOfView[cell]){
					h.interrupt();
					break;
				}
			}
		}
	}

}
