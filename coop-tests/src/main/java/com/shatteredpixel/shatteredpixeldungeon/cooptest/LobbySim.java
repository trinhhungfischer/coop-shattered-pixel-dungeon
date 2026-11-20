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

package com.shatteredpixel.shatteredpixeldungeon.cooptest;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopClient;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopLobbyScene;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopNet;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.CoopOnline;
import com.shatteredpixel.shatteredpixeldungeon.coop.net.LanDiscovery;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.List;

/**
 * Headless run of the online lobby inside the real game: host a game from the lobby scene, join
 * it with a client, pick classes, get ready, start, and check the host's run has both heroes.
 * Everything binds to the loopback address.
 */
public class LobbySim {

	static int frames = 0;
	static int phase = 0;
	static CoopClient client;
	static volatile int clientSlot = -1;
	static volatile List<CoopNet.Player> clientStart = null;

	public static void main( String[] args ) throws Exception {
		InetAddress lo = InetAddress.getByName( "127.0.0.1" );
		CoopLobbyScene.bindAddress = lo;
		CoopLobbyScene.hostPort = 0;
		LanDiscovery.listenAddress = lo;
		LanDiscovery.target = lo;
		LanDiscovery.port = 23999;
		CoopSim.customDriver = LobbySim::frame;
		CoopSim.launch();
	}

	static Object call( Object target, String name, Class<?>[] types, Object... args ) throws Exception {
		Method m = target.getClass().getDeclaredMethod( name, types );
		m.setAccessible( true );
		return m.invoke( target, args );
	}

	static void step( String what ){
		System.out.println( "[LOBBY] " + what );
	}

	static void frame(){
		frames++;
		if (frames > 30000){
			CoopSim.problem( "lobby scenario timed out in phase " + phase );
			CoopSim.finish();
		}
		try {
			switch (phase){
				case 0:
					if (frames < 10) return;
					GamesInProgress.curSlot = 1;
					GamesInProgress.selectedClass = HeroClass.WARRIOR;
					Game.switchScene( CoopLobbyScene.class );
					phase = 1;
					return;
				case 1:
					if (!(Game.scene() instanceof CoopLobbyScene)) return;
					call( Game.scene(), "hostGame", new Class[0] );
					if (CoopOnline.host == null){
						CoopSim.problem( "hosting from the lobby failed" );
						CoopSim.finish();
						return;
					}
					step( "hosting on port " + CoopOnline.host.port() );
					client = new CoopClient( Game.versionCode, new CoopClient.Events() {
						@Override public void onWelcome( int slot ){ clientSlot = slot; }
						@Override public void onStart( List<CoopNet.Player> players ){ clientStart = players; }
					});
					client.connect( "127.0.0.1", CoopOnline.host.port(), "TestClient", 3000 );
					phase = 2;
					return;
				case 2:
					if (clientSlot <= 0) return;
					step( "client joined in slot " + clientSlot );
					client.pick( "ROGUE" );
					client.setReady( true );
					phase = 3;
					return;
				case 3:
					if (CoopOnline.host.playerCount() != 2) return;
					List<CoopNet.Player> ps = CoopOnline.host.players();
					if (!ps.get(1).ready || !ps.get(1).heroClass.equals( "ROGUE" )) return;
					step( "lobby has the client ready as a Rogue" );
					call( Game.scene(), "setReady", new Class[]{ boolean.class }, true );
					phase = 4;
					return;
				case 4:
					if (!CoopOnline.host.allReady()) return;
					if (!CoopOnline.host.startGame()){
						CoopSim.problem( "host could not start although everyone is ready" );
						CoopSim.finish();
						return;
					}
					call( Game.scene(), "onStarted", new Class[]{ List.class }, CoopOnline.host.players() );
					step( "host started the run" );
					phase = 5;
					return;
				case 5:
					if (!(Game.scene() instanceof GameScene) || Dungeon.hero == null || !Coop.active()) return;
					if (Coop.size() != 2) CoopSim.problem( "expected 2 heroes in the online run, got " + Coop.size() );
					else {
						if (Coop.get(0).heroClass != HeroClass.WARRIOR) CoopSim.problem( "host hero is " + Coop.get(0).heroClass );
						if (Coop.get(1).heroClass != HeroClass.ROGUE) CoopSim.problem( "client hero is " + Coop.get(1).heroClass );
					}
					if (clientStart == null || clientStart.size() != 2) CoopSim.problem( "client did not get the start with both players" );
					step( "run started with " + Coop.size() + " heroes: " + Coop.get(0).heroClass + ", " + Coop.get(1).heroClass );
					CoopOnline.leave();
					CoopSim.finish();
			}
		} catch (Exception e){
			CoopSim.problem( "lobby scenario crashed: " + CoopSim.stack( e ) );
			CoopSim.finish();
		}
	}
}
