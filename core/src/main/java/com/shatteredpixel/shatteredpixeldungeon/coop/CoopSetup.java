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

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;

import java.util.ArrayList;

/**
 * Settings for the next new run, filled in by the hero select UI.
 * Dungeon.init() reads this to create the extra party heroes. Empty means a solo run.
 */
public class CoopSetup {

	//classes of the heroes after the primary one (the primary uses GamesInProgress.selectedClass)
	public static final ArrayList<HeroClass> extraClasses = new ArrayList<>();

	public static boolean pending(){
		return !extraClasses.isEmpty();
	}

	public static void clear(){
		extraClasses.clear();
	}
}
