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

import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.watabou.noosa.Image;
import com.watabou.utils.Callback;

/**
 * Hero select side of hotseat coop: a player count toggle, and after the first hero is chosen,
 * one class pick per extra player. The result goes into CoopSetup.extraClasses.
 */
public class CoopHeroSelect {

	public static final int MAX_PLAYERS = 4;

	//1 means solo. Kept while the game is open so the toggle remembers its state.
	public static int players = 1;

	public static void cyclePlayers(){
		players++;
		if (players > MAX_PLAYERS) players = 1;
	}

	public static String toggleText(){
		if (players <= 1) return Messages.get(CoopHeroSelect.class, "coop_off");
		return Messages.get(CoopHeroSelect.class, "coop_on", players);
	}

	//call instead of starting the run directly. In solo this clears the setup and starts right away.
	public static void start( Callback startRun ){
		CoopSetup.clear();
		if (players <= 1){
			startRun.call();
		} else {
			pick( 2, startRun );
		}
	}

	private static void pick( final int player, final Callback startRun ){
		final HeroClass[] classes = HeroClass.values();
		String[] names = new String[classes.length];
		for (int i = 0; i < classes.length; i++){
			names[i] = Messages.titleCase(classes[i].title());
		}

		ShatteredPixelDungeon.scene().addToFront(new WndOptions(
				Messages.get(CoopHeroSelect.class, "pick_title", player),
				Messages.get(CoopHeroSelect.class, "pick_desc", player),
				names ){
			@Override
			protected boolean enabled( int index ) {
				return classes[index].isUnlocked();
			}

			@Override
			protected boolean hasIcon( int index ) {
				return true;
			}

			@Override
			protected Image getIcon( int index ) {
				return new Image(classes[index].spritesheet(), 0, 90, 12, 15);
			}

			@Override
			protected void onSelect( int index ) {
				CoopSetup.extraClasses.add( classes[index] );
				if (player < players){
					pick( player+1, startRun );
				} else {
					startRun.call();
				}
			}

			@Override
			public void onBackPressed() {
				super.onBackPressed();
				CoopSetup.clear();
			}
		});
	}
}
