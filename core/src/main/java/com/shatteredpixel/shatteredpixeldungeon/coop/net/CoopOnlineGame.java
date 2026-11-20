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

package com.shatteredpixel.shatteredpixeldungeon.coop.net;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopSetup;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.ActionIndicator;
import com.watabou.noosa.Game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Starting an online run once the lobby is done. The host runs the real game with one hero per
 * player (party index == player slot order). Clients show the host's game (M3).
 */
public class CoopOnlineGame {

	public static void start(){
		if (CoopOnline.isHost()) startHost();
		else if (CoopOnline.isClient()) CoopRemote.startClient();
	}

	private static void startHost(){
		List<CoopNet.Player> players = new ArrayList<>( CoopOnline.players );
		players.sort( Comparator.comparingInt( p -> p.slot ) );
		//party index of each slot, used to route commands to heroes
		CoopRemote.slotsByPartyIndex = new int[players.size()];
		for (int i = 0; i < players.size(); i++) CoopRemote.slotsByPartyIndex[i] = players.get(i).slot;

		GamesInProgress.selectedClass = HeroClass.valueOf( players.get(0).heroClass );
		CoopSetup.clear();
		for (int i = 1; i < players.size(); i++){
			CoopSetup.extraClasses.add( HeroClass.valueOf( players.get(i).heroClass ) );
		}

		CoopRemote.startHost();
		Dungeon.hero = null;
		Dungeon.daily = Dungeon.dailyReplay = false;
		Dungeon.initSeed();
		ActionIndicator.clearAction();
		InterlevelScene.mode = InterlevelScene.Mode.DESCEND;
		Game.switchScene( InterlevelScene.class );
	}
}
