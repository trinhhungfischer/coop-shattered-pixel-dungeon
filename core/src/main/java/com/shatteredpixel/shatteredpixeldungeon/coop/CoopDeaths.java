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
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;

/**
 * Hero deaths caused by things other than a direct attack (mob abilities, buffs, beams...).
 *
 * The solo pattern is:
 *     if (ch == Dungeon.hero && !ch.isAlive()) { Dungeon.fail(cause); GLog.n(msg); }
 * In coop the victim may be any party hero (not only the active one), and the cause must be
 * recorded for that hero via CoopCombat.heroKilled. Replace it with:
 *     if (CoopDeaths.heroDied(ch)) { CoopDeaths.fail(ch, cause); CoopDeaths.log(ch, msg); }
 * Solo behaviour is unchanged.
 */
public class CoopDeaths {

	//solo: ch == Dungeon.hero && !ch.isAlive(). Coop: ch is any party hero and is dead.
	public static boolean heroDied( Char ch ){
		return CoopCore.isPartyHero(ch) && !ch.isAlive();
	}

	//solo: Dungeon.fail(cause). Coop: records the cause for that hero, the run ends with the last one.
	public static void fail( Char victim, Object cause ){
		if (Coop.active() && Coop.isHero(victim)){
			CoopCombat.heroKilled( (Hero) victim, cause );
		} else {
			Dungeon.fail( cause );
		}
	}

	//solo: GLog.n(msg). Coop: the message names the hero that died ("You ..." texts are per hero)
	public static void log( Char victim, String msg ){
		if (Coop.active() && Coop.isHero(victim)){
			String s = Messages.get(CoopDeaths.class, "ondeath", victim.name(), msg);
			if (!s.equals(Messages.NO_TEXT_FOUND)) msg = s;
		}
		GLog.n( msg );
	}

	//shorthand for fail + log
	public static void died( Char victim, Object cause, String msg ){
		fail( victim, cause );
		log( victim, msg );
	}

	//Hero.die(): a hero that became a spectator still records its cause of death and runs the
	//cause's Doom.onDeath() (death message, badges). Dungeon.fail inside onDeath returns early
	//while other heroes live.
	public static void onSpectatorDeath( Hero h, Object cause ){
		if (!Coop.active()) return;
		CoopCombat.heroKilled( h, cause );
		if (cause instanceof Hero.Doom){
			((Hero.Doom) cause).onDeath();
		}
	}
}
