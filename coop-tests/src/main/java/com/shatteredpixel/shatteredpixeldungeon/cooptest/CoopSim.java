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

import com.badlogic.gdx.ApplicationLogger;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3FileHandle;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Preferences;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Invulnerability;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.coop.CoopSetup;
import com.shatteredpixel.shatteredpixeldungeon.desktop.DesktopPlatformSupport;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.utils.FileUtils;
import com.watabou.utils.PathFinder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Plays a 2-hero hotseat coop run with a simple bot, without opening a window.
 * Checks invariants every turn and reports crashes, soft-locks and broken state.
 *
 * Args: [seed] [target depth] [max actions] [party size, 1 = solo]
 * Exit code 0 = passed, 1 = problems found.
 */
public class CoopSim {

	static long seed = 1234;
	static int targetDepth = 6;
	static int maxActions = 4000;
	static int partySize = 2;

	static final List<String> problems = new ArrayList<>();
	//other headless scenarios (e.g. LobbySim) drive the game with their own per-frame logic
	static Runnable customDriver = null;
	static int errorsLogged = 0;

	//starts the headless game; the caller sets customDriver first
	static void launch() throws Exception {
		File dir = java.nio.file.Files.createTempDirectory("spd-coopsim").toFile();
		String base = dir.getAbsolutePath().replace('\\', '/') + "/";
		Thread.setDefaultUncaughtExceptionHandler( (t, e) -> {
			problem("uncaught exception on " + t.getName() + ": " + stack(e));
			finish();
		});
		Game.version = "4.0.2-INDEV-coopsim";
		Game.versionCode = 922;
		HeadlessApplicationConfiguration cfg = new HeadlessApplicationConfiguration();
		cfg.updatesPerSecond = 2000;
		new HeadlessApplication( new SimGame(base), cfg );
	}

	public static void main( String[] args ) throws Exception {
		if (args.length > 0) seed = Long.parseLong(args[0]);
		if (args.length > 1) targetDepth = Integer.parseInt(args[1]);
		if (args.length > 2) maxActions = Integer.parseInt(args[2]);
		if (args.length > 3) partySize = Integer.parseInt(args[3]);

		File dir = java.nio.file.Files.createTempDirectory("spd-coopsim").toFile();
		String base = dir.getAbsolutePath().replace('\\', '/') + "/";

		Thread.setDefaultUncaughtExceptionHandler( (t, e) -> {
			problem("uncaught exception on " + t.getName() + ": " + stack(e));
			finish();
		});

		Game.version = "4.0.2-INDEV-coopsim"; //INDEV skips story screens and fades
		Game.versionCode = 922;

		HeadlessApplicationConfiguration cfg = new HeadlessApplicationConfiguration();
		cfg.updatesPerSecond = 2000;
		new HeadlessApplication( new SimGame(base), cfg );
	}

	static synchronized void problem( String s ){
		problems.add( s );
		System.out.println("[SIM] PROBLEM: " + s);
	}

	static String stack( Throwable e ){
		java.io.StringWriter sw = new java.io.StringWriter();
		e.printStackTrace(new java.io.PrintWriter(sw));
		String s = sw.toString();
		return s.length() > 3000 ? s.substring(0, 3000) : s;
	}

	static synchronized void finish(){
		System.out.println("[SIM] ===== summary =====");
		System.out.println("[SIM] seed=" + seed + " party=" + partySize + " depth=" + Dungeon.depth + " actions=" + Driver.actions
				+ " saveLoads=" + Driver.reloads + " floorsDescended=" + Driver.descents
				+ " heroSwitches=" + Driver.switches + " focusSwitches=" + Driver.focusSwitches
				+ " pausedChecks=" + Driver.pausedChecks + " errorsLogged=" + errorsLogged);
		if (problems.isEmpty()){
			System.out.println("[SIM] PASSED");
		} else {
			System.out.println("[SIM] FAILED with " + problems.size() + " problem(s)");
		}
		System.out.flush();
		Runtime.getRuntime().halt( problems.isEmpty() ? 0 : 1 );
	}

	static class SimPlatform extends DesktopPlatformSupport {
		@Override public void updateDisplaySize() {}
		@Override public void updateSystemUI() {}
	}

	static class SimGame extends ShatteredPixelDungeon {

		final String base;

		SimGame( String base ){
			super( new SimPlatform() );
			this.base = base;
		}

		@Override
		public void create() {
			HeadlessStubs.install();
			Gdx.app.setApplicationLogger( new ApplicationLogger() {
				@Override public void log(String tag, String message) { if (tag.startsWith("COOP") || tag.equals("SIM")) System.out.println("[" + tag + "] " + message); }
				@Override public void log(String tag, String message, Throwable exception) { log(tag, message + " " + stack(exception)); }
				@Override public void error(String tag, String message) {
					errorsLogged++;
					problem("logged error [" + tag + "] " + message);
				}
				@Override public void error(String tag, String message, Throwable exception) { error(tag, message + " " + stack(exception)); }
				@Override public void debug(String tag, String message) {}
				@Override public void debug(String tag, String message, Throwable exception) {}
			});

			SPDSettings.set( new Lwjgl3Preferences( new Lwjgl3FileHandle( base + SPDSettings.DEFAULT_PREFS_FILE, Files.FileType.Absolute ) ) );
			FileUtils.setDefaultFileProperties( Files.FileType.Absolute, base );
			SPDSettings.intro( false );

			super.create();
			resize( HeadlessStubs.WIDTH, HeadlessStubs.HEIGHT );
		}

		@Override
		public void render() {
			HeadlessStubs.newFrame();
			try {
				if (customDriver != null) customDriver.run();
				else Driver.frame();
			} catch (Throwable t){
				problem("driver crashed: " + stack(t));
				CoopSim.finish(); //not Game.finish(), which would quietly exit with code 0
			}
			super.render();
		}
	}

	/**
	 * The bot. Runs on the render thread between frames, like player input does.
	 */
	static class Driver {

		static int frames = 0;
		static int actions = 0;
		static int reloads = 0;
		static int descents = 0;
		static int switches = 0;

		static boolean started = false;
		static int lastActionFrame = 0;
		static long lastActionTime = System.currentTimeMillis();
		static int lastDepth = 0;
		static Hero lastActive = null;
		static int activeSinceFrame = 0;
		static boolean deathTestDone = false;
		static boolean pendingReload = false;

		static void frame(){
			frames++;

			if (!started){
				//let the title / welcome scene settle, then start a coop run directly
				if (frames < 10) return;
				started = true;
				startRun();
				return;
			}

			if (!(Game.scene() instanceof GameScene) || Dungeon.hero == null || Dungeon.level == null){
				if (System.currentTimeMillis() - lastActionTime > 60_000){
					problem("stuck outside the game scene for 60s (scene=" + Game.scene() + ")");
					finish();
				}
				return;
			}

			if (Dungeon.depth != lastDepth){
				if (lastDepth != 0 && Dungeon.depth > lastDepth) descents++;
				System.out.println("[SIM] now on depth " + Dungeon.depth + " (party alive " + Coop.aliveHeroes().size() + ")");
				lastDepth = Dungeon.depth;
			}

			if (Dungeon.hero != lastActive){
				lastActive = Dungeon.hero;
				activeSinceFrame = frames;
				switches++;
			}

			if (Coop.allDead()){
				//death test reached the end: game over must not crash, give it some frames
				if (frames - lastActionFrame > 200) finish();
				return;
			}

			Hero h = Dungeon.hero;
			//with turn groups the player controls the focus hero; another group's hero can hold the
			//pointer for a moment while it acts on its own
			if (com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime.enabled()
					&& com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime.focus() != null
					&& h != com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime.focus()){
				if (frames - lastActionFrame > 3000){
					problem("soft-lock: the controlled hero never got the turn back. " + state() + " | " + actorDump());
					finish();
				}
				return;
			}
			if (!h.ready || h.curAction != null) {
				if (frames - lastActionFrame > 3000){
					problem("soft-lock: no hero got control for 3000 frames. " + state() + " | " + actorDump());
					finish();
				}
				return;
			}

			//UI work for a hero switch happens on the next render frames, and a player
			//only gives input once the screen shows the hero whose turn it is
			if (frames - activeSinceFrame < 3) return;
			if (partySize > 1 && !("P" + (Coop.indexOf(h)+1)).equals(shownHero())) return;

			checkInvariants();

			if (pendingReload){
				pendingReload = false;
				reload();
				return;
			}

			if (deathPhase == 1){
				//the hero that died on its own turn must hand control to the survivor
				if (h == killedOnTurn || Coop.isDead(h)){
					problem("control stayed on the hero that died during its turn. " + state());
					finish();
					return;
				}
				System.out.println("[SIM] survivor P" + (Coop.indexOf(h)+1) + " got control after the death");
				if (Coop.aliveHeroes().size() > 1){
					//bigger parties: keep killing whoever has the turn until one hero is left
					Buff b = h.buff( Invulnerability.class );
					if (b != null) b.detach();
					killedOnTurn = h;
					h.damage( h.HP + 1000, new Object() );
					System.out.println("[SIM] P" + (Coop.indexOf(h)+1) + " died on its own turn, " + Coop.aliveHeroes().size() + " alive");
					lastActionFrame = frames;
					lastActionTime = System.currentTimeMillis();
					return;
				}
				deathPhase = 2;
				for (int i = 0; i < 3; i++){
					actions++;
					h.rest( false );
					h.next();
				}
				return;
			}
			if (deathPhase == 2){
				deathPhase = 3;
				Buff b = h.buff( Invulnerability.class );
				if (b != null) b.detach();
				h.damage( h.HP + 1000, new Object() );
				System.out.println("[SIM] last hero died, waiting for game over");
				lastActionFrame = frames;
				lastActionTime = System.currentTimeMillis();
				return;
			}

			if (Dungeon.depth >= targetDepth && !deathTestDone){
				deathTest();
				return;
			}

			if (actions >= maxActions){
				problem("did not reach depth " + targetDepth + " within " + maxActions + " actions. " + state());
				finish();
				return;
			}

			if (Dungeon.depth != progressDepth){
				progressDepth = Dungeon.depth;
				lastProgressActions = actions;
			} else if (actions - lastProgressActions == 400){
				System.out.println("[SIM] no progress for 400 actions on depth " + Dungeon.depth + ": " + reachability() + " " + state());
				System.out.println("[SIM] actor dump: " + actorDump());
			}

			//a failed move costs no time, so the same hero would keep the turn forever: wait instead
			float now = com.shatteredpixel.shatteredpixeldungeon.actors.Actor.now();
			//(heroes can also take turns failing moves, so this watches game time, not just one hero)
			if (now == lastActorNow && sameTimeActions++ >= 2 * Coop.aliveHeroes().size()){
				sameTimeActions = 0;
				h.rest( false );
				h.next();
			} else {
				if (now != lastActorNow) sameTimeActions = 0;
				act( h );
			}
			lastActor = h;
			lastActorNow = now;
			actions++;
			lastActionFrame = frames;
			lastActionTime = System.currentTimeMillis();

			if (actions % 250 == 0) pendingReload = true;
		}

		static void startRun(){
			GamesInProgress.curSlot = 1;
			GamesInProgress.selectedClass = HeroClass.WARRIOR;
			CoopSetup.clear();
			HeroClass[] extra = { HeroClass.MAGE, HeroClass.ROGUE, HeroClass.HUNTRESS };
			for (int i = 1; i < partySize; i++) CoopSetup.extraClasses.add( extra[i-1] );
			Dungeon.hero = null;
			Dungeon.daily = Dungeon.dailyReplay = false;
			SPDSettings.customSeed( Long.toString(seed) );
			Dungeon.initSeed();
			InterlevelScene.mode = InterlevelScene.Mode.DESCEND;
			Game.switchScene( InterlevelScene.class );
			lastActionFrame = frames;
			lastActionTime = System.currentTimeMillis();
		}

		static void reload(){
			try {
				Dungeon.saveAll();
			} catch (Exception e){
				problem("save failed: " + stack(e));
				return;
			}
			int[] before = positions();
			reloads++;
			System.out.println("[SIM] save + reload #" + reloads + " on depth " + Dungeon.depth);
			expectedAfterReload = before;
			InterlevelScene.mode = InterlevelScene.Mode.CONTINUE;
			Game.switchScene( InterlevelScene.class );
			lastActionFrame = frames;
			lastActionTime = System.currentTimeMillis();
			lastActive = null;
		}

		static int[] expectedAfterReload = null;
		static int sharedCellChecks = 0;
		static boolean alignWarned = false;
		static int fightSplitChecks = 0;
		static int pausedDepth = -1;
		static int pausedReloads = -1;

		//true when every clock was moved down together (Actor.fixTime on save), not a paused group acting
		//a paused hero pulled into a fight is moved up to the fight's clock (and may split off
		//again right after the fight): its clock then equals a teammate's
		static boolean alignedToTeammate( Hero x, float now ){
			for (Hero o : Coop.aliveHeroes()){
				if (o != x && Math.abs( com.shatteredpixel.shatteredpixeldungeon.actors.Actor.coopTime( o ) - now ) < 0.001f) return true;
			}
			return false;
		}
		static final java.util.HashMap<Hero, Float> pausedClock = new java.util.HashMap<>();
		static int pausedChecks = 0;
		static int focusSwitches = 0;
		static Hero lastActor = null;
		static float lastActorNow = -1;
		static int sameTimeActions = 0;

		static int[] positions(){
			List<Hero> hs = Coop.heroes();
			int[] p = new int[hs.size()];
			for (int i = 0; i < p.length; i++) p[i] = hs.get(i).pos;
			return p;
		}

		static void checkInvariants(){
			if (partySize > 1 && !Coop.active()){
				problem("coop party was lost (Coop.active() is false). " + state());
				finish();
				return;
			}
			if (expectedAfterReload != null){
				int[] now = positions();
				if (now.length != expectedAfterReload.length){
					problem("party size changed across save/load: " + expectedAfterReload.length + " -> " + now.length);
				} else {
					for (int i = 0; i < now.length; i++){
						if (now[i] != expectedAfterReload[i]){
							problem("P" + (i+1) + " moved across save/load: " + expectedAfterReload[i] + " -> " + now[i]);
						}
					}
				}
				expectedAfterReload = null;
			}

			Hero active = Dungeon.hero;
			boolean overlap = false;
			for (Hero x : Coop.aliveHeroes()) for (Hero y : Coop.aliveHeroes()) if (x != y && x.pos == y.pos) overlap = true;
			if (!overlap) sharedCellChecks = 0;
			if (Coop.isDead(active) || !active.isAlive()){
				problem("active hero is dead while others live. " + state());
			}
			if (active.sprite == null || active.sprite.parent == null){
				problem("active hero has no sprite in the scene. " + state());
			} else {
				if (!active.sprite.visible) problem("active hero sprite is hidden. " + state());
				if (Camera.main != null){
					//what matters to the player: the hero whose turn it is is on screen
					com.watabou.utils.PointF c = active.sprite.center();
					Camera cam = Camera.main;
					float margin = 8;
					boolean onScreen = c.x >= cam.scroll.x - margin && c.x <= cam.scroll.x + cam.width + margin
							&& c.y >= cam.scroll.y - margin && c.y <= cam.scroll.y + cam.height + margin;
					if (!onScreen){
						problem("active hero is off screen. sprite=" + (int)c.x + "," + (int)c.y
								+ " view=" + (int)cam.scroll.x + "," + (int)cam.scroll.y + " " + cam.width + "x" + cam.height
								+ " followsActive=" + (cam.followTarget() == active.sprite)
								+ " follow=" + (cam.followTarget() == null ? "none" : cam.followTarget().getClass().getSimpleName())
								+ " followsP=" + followedHero() + " shownP=" + shownHero()
								+ ". " + state());
					}
				}
			}
			for (Hero h : Coop.aliveHeroes()){
				if (h.sprite == null || h.sprite.parent == null){
					problem("P" + (Coop.indexOf(h)+1) + " is alive but has no sprite. " + state());
				}
				if (h.pos < 0 || h.pos >= Dungeon.level.length() || !Dungeon.level.passable[h.pos] && !Dungeon.level.avoid[h.pos]){
					problem("P" + (Coop.indexOf(h)+1) + " stands on an invalid cell " + h.pos + ". " + state());
				}
				for (Hero o : Coop.aliveHeroes()){
					if (o != h && o.pos == h.pos){
						//a swap moves one hero before the other, so only a lasting overlap is a bug
						sharedCellChecks++;
						if (sharedCellChecks == 5) problem("two heroes share cell " + h.pos + " for several turns. " + state());
						return;
					}
				}
			}
			if (Dungeon.level.heroFOV != null && !Dungeon.level.heroFOV[active.pos]){
				problem("active hero cannot see its own cell (FOV not updated). " + state());
			}
			if (Dungeon.quickslot == null) problem("quickslot is null");

			//turn groups: heroes in one group share a clock, a paused group's clock stands still
			if (com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime.enabled()){
				List<Hero> alive = Coop.aliveHeroes();
				for (Hero x : alive){
					for (Hero y : alive){
						if (x == y || Coop.indexOf(x) > Coop.indexOf(y)) continue;
						if (com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime.sameGroup(x, y)){
							float dt = Math.abs(com.shatteredpixel.shatteredpixeldungeon.actors.Actor.coopTime(x) - com.shatteredpixel.shatteredpixeldungeon.actors.Actor.coopTime(y));
							if (dt > 6 && !alignWarned){
								alignWarned = true;
								problem("P" + (Coop.indexOf(x)+1) + " and P" + (Coop.indexOf(y)+1) + " share a turn group but their clocks are " + dt + " apart. " + state());
							}
						}
					}
				}
				//heroes in the same fight (or facing an awake boss) must take turns together
				boolean split = false;
				String why = "";
				for (Mob m : Dungeon.level.mobs){
					if (!m.isAlive() || m.alignment != com.shatteredpixel.shatteredpixeldungeon.actors.Char.Alignment.ENEMY || m.state != m.HUNTING) continue;
					boolean boss = com.shatteredpixel.shatteredpixeldungeon.actors.Char.hasProp(m, com.shatteredpixel.shatteredpixeldungeon.actors.Char.Property.BOSS);
					Hero first = null;
					for (Hero x : alive){
						boolean engaged = boss || m.isTargeting(x) || (x.fieldOfView != null && m.pos < x.fieldOfView.length && x.fieldOfView[m.pos]);
						if (!engaged) continue;
						if (first == null) first = x;
						else if (!com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime.sameGroup(first, x)){ split = true; why = m.getClass().getSimpleName(); }
					}
				}
				fightSplitChecks = split ? fightSplitChecks + 1 : 0;
				if (fightSplitChecks == 4){
					problem("heroes in the same fight (" + why + ") are in different turn groups. " + state());
				}

				for (Hero x : alive){
					boolean paused = x != com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime.focus() && com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime.isWaiting(x) && !com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime.sameGroup(x, com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime.focus());
					Float before = pausedClock.get(x);
					float now = com.shatteredpixel.shatteredpixeldungeon.actors.Actor.coopTime(x);
					if (paused){
						if (pausedDepth != Dungeon.depth || pausedReloads != reloads){
							pausedClock.clear();
							pausedDepth = Dungeon.depth;
							pausedReloads = reloads;
							before = null;
						}
						if (before != null && now > before + 0.001f && !alignedToTeammate(x, now)){
							problem("P" + (Coop.indexOf(x)+1) + "'s group is paused but its clock moved " + before + " -> " + now + ". " + state());
						}
						pausedClock.put(x, now);
						pausedChecks++;
					} else {
						pausedClock.remove(x);
					}
				}
			}
		}

		static final java.util.HashMap<Hero, Integer> lastPos = new java.util.HashMap<>();
		static final java.util.HashMap<Hero, Integer> lastTarget = new java.util.HashMap<>();
		static final java.util.HashMap<Hero, Integer> stuckCount = new java.util.HashMap<>();
		static final java.util.HashMap<Integer, Integer> ignored = new java.util.HashMap<>();
		static final java.util.HashMap<Integer, Integer> keyTries = new java.util.HashMap<>();

		static void act( Hero h ){
			Level level = Dungeon.level;

			//test cheats: heroes can't die during the walk, and they know the map so they can path
			for (Hero p : Coop.aliveHeroes()){
				Buff.prolong( p, Invulnerability.class, 5f );
			}
			for (int i = 0; i < level.length(); i++) level.mapped[i] = true;

			//fight visible enemies first (skipping ones the bot couldn't get to)
			Integer stuck = stuckCount.get(h);
			if (stuck != null && stuck >= 6 && lastTarget.get(h) != null){
				ignored.put(lastTarget.get(h), actions + 150);
				stuckCount.put(h, 0);
			}
			Mob target = null;
			for (Mob m : level.mobs){
				Integer until = ignored.get(m.id());
				if (until != null && until > actions) continue;
				if (m.alignment == com.shatteredpixel.shatteredpixeldungeon.actors.Char.Alignment.ENEMY
						&& m.isAlive() && h.fieldOfView != null && h.fieldOfView[m.pos]
						&& (target == null || level.distance(h.pos, m.pos) < level.distance(h.pos, target.pos))){
					target = m;
				}
			}
			if (target != null && level.distance(h.pos, target.pos) <= 6){
				Integer lp = lastPos.get(h);
				if (lp != null && lp == h.pos && Integer.valueOf(target.id()).equals(lastTarget.get(h))){
					stuckCount.put(h, stuckCount.getOrDefault(h, 0) + 1);
				} else {
					stuckCount.put(h, 0);
				}
				lastPos.put(h, h.pos);
				lastTarget.put(h, target.id());
				doHandle( h, target.pos );
				return;
			}
			lastTarget.remove(h);

			//keys (iron keys for locked rooms, the skeleton key a boss drops for the exit) go first
			for (com.shatteredpixel.shatteredpixeldungeon.items.Heap heap : level.heaps.valueList()){
				if ((heap.peek() instanceof com.shatteredpixel.shatteredpixeldungeon.items.keys.IronKey
						|| heap.peek() instanceof com.shatteredpixel.shatteredpixeldungeon.items.keys.WornKey)
						&& heap.type == com.shatteredpixel.shatteredpixeldungeon.items.Heap.Type.HEAP
						&& (level.visited[heap.pos] || h.fieldOfView[heap.pos])){
					Integer until = ignored.get(-heap.pos);
					if (until != null && until > actions) continue;
					//give up on keys the bot can't reach, or has chased for too long
					int tries = keyTries.merge(heap.pos, 1, Integer::sum);
					boolean[] pass = com.watabou.utils.BArray.or( level.passable, level.avoid, null );
					PathFinder.buildDistanceMap( heap.pos, pass );
					if (PathFinder.distance[h.pos] == Integer.MAX_VALUE || tries > 150){
						ignored.put(-heap.pos, actions + 500);
						keyTries.remove(heap.pos);
						continue;
					}
					if (h.pos == heap.pos){
						//standing on it but it did not get picked up: don't try forever
						ignored.put(-heap.pos, actions + 300);
						continue;
					}
					doHandle( h, heap.pos );
					return;
				}
			}

			//Tengu's pause phase: he vanishes and the arena opens once a hero walks back up the hallway
			if (level.locked && level instanceof com.shatteredpixel.shatteredpixeldungeon.levels.PrisonBossLevel){
				boolean tenguHere = false;
				for (Mob m : level.mobs) if (m instanceof com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Tengu) tenguHere = true;
				if (!tenguHere){
					doHandle( h, 10 + 8 * level.width() );
					return;
				}
			}

			//a sealed boss floor only opens once the boss is beaten: go find it
			if (level.locked){
				for (Mob m : level.mobs){
					if (m.isAlive() && com.shatteredpixel.shatteredpixeldungeon.actors.Char.hasProp(m, com.shatteredpixel.shatteredpixeldungeon.actors.Char.Property.BOSS)
							&& m.alignment == com.shatteredpixel.shatteredpixeldungeon.actors.Char.Alignment.ENEMY){
						doHandle( h, m.pos );
						return;
					}
				}
			}

			//getTransition falls back to the entrance when a level has no exit yet (boss floors)
			LevelTransition exit = level.getTransition( LevelTransition.Type.REGULAR_EXIT );
			if (exit != null && exit.type != LevelTransition.Type.REGULAR_EXIT) exit = null;
			if (exit != null){
				//some boss floors declare their exit inside walls until the boss is beaten
				boolean[] pass = com.watabou.utils.BArray.or( level.passable, level.avoid, null );
				pass[exit.cell()] = true;
				PathFinder.buildDistanceMap( exit.cell(), pass );
				if (PathFinder.distance[h.pos] == Integer.MAX_VALUE) exit = null;
			}
			if (exit == null){
				//no way down yet: go for the boss, otherwise explore towards the far end of the level
				Mob boss = null;
				for (Mob m : level.mobs){
					if (m.isAlive() && com.shatteredpixel.shatteredpixeldungeon.actors.Char.hasProp(m, com.shatteredpixel.shatteredpixeldungeon.actors.Char.Property.BOSS)
							&& m.alignment == com.shatteredpixel.shatteredpixeldungeon.actors.Char.Alignment.ENEMY){
						boss = m;
					}
				}
				int goal = boss != null ? boss.pos : farCell( level );
				if (boss == null){
					//locked rooms (like Tengu's cell): fetch the iron key, then open the door
					int key = -1, door = -1;
					for (com.shatteredpixel.shatteredpixeldungeon.items.Heap heap : level.heaps.valueList()){
						if (heap.peek() instanceof com.shatteredpixel.shatteredpixeldungeon.items.keys.IronKey) key = heap.pos;
					}
					for (int i = 0; i < level.length(); i++){
						if (level.map[i] == com.shatteredpixel.shatteredpixeldungeon.levels.Terrain.LOCKED_DOOR) door = i;
					}
					if (key != -1){
						doHandle( h, key );
						return;
					} else if (door != -1 && level instanceof com.shatteredpixel.shatteredpixeldungeon.levels.PrisonBossLevel){
						goal = door;
					}
				}
				if (boss == null && level instanceof com.shatteredpixel.shatteredpixeldungeon.levels.PrisonBossLevel
						&& level.map[goal] != com.shatteredpixel.shatteredpixeldungeon.levels.Terrain.LOCKED_DOOR){
					//Tengu waits in his cell at the end of the hallway: walking in starts the fight
					goal = 10 + 27 * level.width();
				}
				if (actions % 200 == 0){
					System.out.println("[SIM] no exit on depth " + Dungeon.depth + ": boss=" + (boss != null) + " goal=" + goal
							+ " goalTerrain=" + level.map[goal] + " heroPos=" + h.pos + " keyHeaps=" + countKeys(level)
							+ " ironKeys=" + com.shatteredpixel.shatteredpixeldungeon.journal.Notes.keyCount(new com.shatteredpixel.shatteredpixeldungeon.items.keys.IronKey(Dungeon.depth)));
				}
				doHandle( h, goal );
				return;
			}

			boolean onExit = exit.inside( h.pos );
			boolean partyGathered = true;
			for (Hero o : Coop.aliveHeroes()){
				if (o == h) continue;
				if (level.distance(o.pos, exit.cell()) > com.shatteredpixel.shatteredpixeldungeon.coop.CoopCore.transitionRange(level, exit)) partyGathered = false;
			}

			//close to the stairs and they are free: stand on them first, making room for the others
			if (!partyGathered && !onExit
					&& level.distance(h.pos, exit.cell()) <= com.shatteredpixel.shatteredpixeldungeon.coop.CoopCore.transitionRange(level, exit)
					&& com.shatteredpixel.shatteredpixeldungeon.actors.Actor.findChar(exit.cell()) == null){
				doHandle( h, exit.cell() );
				return;
			}
			if (!partyGathered && switchToSeparatedTeammate( h )) return;
			if (onExit && !partyGathered){
				//wait for the teammate at the stairs
				h.rest( false );
				h.next();
				return;
			}
			if (!onExit && level.distance(h.pos, exit.cell()) <= com.shatteredpixel.shatteredpixeldungeon.coop.CoopCore.transitionRange(level, exit) && !partyGathered){
				//already close: step onto the stairs if they are free (making room for the others), else wait
				if (com.shatteredpixel.shatteredpixeldungeon.actors.Actor.findChar(exit.cell()) == null){
					doHandle( h, exit.cell() );
				} else {
					h.rest( false );
					h.next();
				}
				return;
			}

			int range = com.shatteredpixel.shatteredpixeldungeon.coop.CoopCore.transitionRange(level, exit);
			if (!onExit && level.distance(h.pos, exit.cell()) > range
					&& com.shatteredpixel.shatteredpixeldungeon.actors.Actor.findChar(exit.cell()) != null
					&& level.distance(h.pos, exit.cell()) <= range + 4){
				//the stairs are taken: find a free spot next to them
				int best = -1;
				for (int i = 0; i < level.length(); i++){
					if (level.distance(i, exit.cell()) <= range && level.passable[i]
							&& com.shatteredpixel.shatteredpixeldungeon.actors.Actor.findChar(i) == null
							&& (best == -1 || level.distance(h.pos, i) < level.distance(h.pos, best))){
						best = i;
					}
				}
				if (best != -1){
					//teammates block paths: step towards the spot and swap with a teammate in the way
					boolean[] pass = com.watabou.utils.BArray.or( level.passable, level.avoid, null );
					PathFinder.buildDistanceMap( best, pass );
					int step = -1;
					for (int i : PathFinder.NEIGHBOURS8){
						int c = h.pos + i;
						if (PathFinder.distance[c] < PathFinder.distance[h.pos] && (step == -1 || PathFinder.distance[c] < PathFinder.distance[step])) step = c;
					}
					Object inWay = step == -1 ? null : com.shatteredpixel.shatteredpixeldungeon.actors.Actor.findChar(step);
					if (inWay instanceof Hero && inWay != h){
						doHandle( h, step );
						return;
					}
					doHandle( h, best );
					return;
				}
			}

			//small random detours so the heroes split up sometimes (different rooms)
			if (actions % 37 < 6){
				int cell = h.pos + PathFinder.NEIGHBOURS8[(actions * 7) % 8];
				if (level.passable[cell] && level.getTransition(cell) == null
						&& com.shatteredpixel.shatteredpixeldungeon.actors.Actor.findChar(cell) == null){
					doHandle( h, cell );
					return;
				}
			}

			doHandle( h, exit.cell() );
		}

		static String neighbors( int pos ){
			StringBuilder sb = new StringBuilder();
			for (int i : PathFinder.NEIGHBOURS8){
				int c = pos + i;
				sb.append(c).append(":").append(Dungeon.level.map[c]).append(Dungeon.level.passable[c] ? "p" : "x").append(" ");
			}
			return sb.toString();
		}

		static int countKeys( Level level ){
			int n = 0;
			for (com.shatteredpixel.shatteredpixeldungeon.items.Heap heap : level.heaps.valueList()){
				for (com.shatteredpixel.shatteredpixeldungeon.items.Item i : heap.items){
					if (i instanceof com.shatteredpixel.shatteredpixeldungeon.items.keys.IronKey) n++;
				}
			}
			return n;
		}

		static Level farLevel = null;
		static int farCellCache = -1;

		//the reachable cell farthest from the entrance, where boss rooms usually are
		static int farCell( Level level ){
			if (farLevel != level){
				farLevel = level;
				boolean[] pass = com.watabou.utils.BArray.or( level.passable, level.avoid, null );
				PathFinder.buildDistanceMap( level.entrance(), pass );
				int best = level.entrance();
				for (int i = 0; i < level.length(); i++){
					if (PathFinder.distance[i] != Integer.MAX_VALUE && PathFinder.distance[i] > PathFinder.distance[best]) best = i;
				}
				farCellCache = best;
			}
			return farCellCache;
		}

		//DOS2 turn groups: a teammate in another group is paused until someone takes control of it
		static boolean switchToSeparatedTeammate( Hero h ){
			if (!com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime.enabled()) return false;
			LevelTransition exit = Dungeon.level.getTransition( LevelTransition.Type.REGULAR_EXIT );
			for (Hero o : Coop.aliveHeroes()){
				if (o == h || com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime.sameGroup(o, h)) continue;
				if (exit != null && Dungeon.level.distance(h.pos, exit.cell()) > 2 && actions % 9 != 0) continue;
				//a teammate already next to the stairs doesn't need the controls
				if (exit != null && Dungeon.level.distance(o.pos, exit.cell()) <= com.shatteredpixel.shatteredpixeldungeon.coop.CoopCore.transitionRange(Dungeon.level, exit)) continue;
				if (com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime.requestFocus( o )){
					focusSwitches++;
					return true;
				}
			}
			return false;
		}

		static void doHandle( Hero h, int cell ){
			boolean handled = h.handle( cell );
			if ((actions - lastProgressActions > 400 && actions % 50 == 0) || (Dungeon.depth == 10 && h.pos == 746 && actions % 5 == 0)){
				int step = Dungeon.findStep( h, cell, Dungeon.level.passable, h.fieldOfView, true );
				System.out.println("[SIM] handle P" + (Coop.indexOf(h)+1) + " pos=" + h.pos + " -> " + cell + " handled=" + handled
						+ " action=" + (h.curAction == null ? "-" : h.curAction.getClass().getSimpleName()) + " step=" + step
						+ (step >= 0 ? " stepChar=" + com.shatteredpixel.shatteredpixeldungeon.actors.Actor.findChar(step) : "")
						+ " neighborsPassable=" + neighbors(h.pos)
						+ " transitionAtCell=" + (Dungeon.level.getTransition(cell) == null ? "-" : Dungeon.level.getTransition(cell).type)
						+ " locked=" + Dungeon.level.locked + " rooted=" + h.rooted
						+ " mapCell=" + Dungeon.level.map[cell] + " passableCell=" + Dungeon.level.passable[cell]);
			}
			if (handled){
				h.next();
				if (h.ready && h.curAction == null){
					//the action finished instantly without using time (e.g. stairs of a sealed floor)
					h.rest( false );
					h.next();
				}
			} else {
				h.rest( false );
				h.next();
			}
		}

		static int deathPhase = 0;
		static Hero killedOnTurn = null;

		//kills the hero whose input is awaited, the way a self-inflicted death on a player's
		//own turn happens (e.g. reading Psionic Blast at low HP), then checks the survivor gets control
		static void deathTest(){
			deathTestDone = true;
			System.out.println("[SIM] reached depth " + Dungeon.depth + ", starting death test");
			List<Hero> alive = Coop.aliveHeroes();
			if (partySize == 1){
				//solo: reaching the target depth without problems is the test
				finish();
				return;
			}
			if (alive.size() < 2){
				problem("expected 2 alive heroes for the death test, found " + alive.size());
				finish();
				return;
			}
			Hero victim = Dungeon.hero;
			for (Hero p : alive){
				Buff b = p.buff( Invulnerability.class );
				if (b != null) b.detach();
			}
			killedOnTurn = victim;
			victim.damage( victim.HP + 1000, new Object() );
			if (!Coop.isDead(victim) || victim.isAlive()){
				problem("hero did not become a spectator after dying. " + state());
			}
			if (Coop.allDead()){
				problem("run ended although one hero is still alive. " + state());
			}
			if (Dungeon.hero == victim){
				problem("active pointer stayed on the dead hero. " + state());
			}
			System.out.println("[SIM] P" + (Coop.indexOf(victim)+1) + " died on its own turn and spectates, " + Coop.aliveHeroes().size() + " alive");
			deathPhase = 1;
			lastActionFrame = frames;
			lastActionTime = System.currentTimeMillis();
		}

		static String actorDump(){
			StringBuilder sb = new StringBuilder();
			try {
				Class<?> ac = com.shatteredpixel.shatteredpixeldungeon.actors.Actor.class;
				java.lang.reflect.Field cur = ac.getDeclaredField("current"); cur.setAccessible(true);
				java.lang.reflect.Field time = ac.getDeclaredField("time"); time.setAccessible(true);
				java.lang.reflect.Field all = ac.getDeclaredField("all"); all.setAccessible(true);
				Object c = cur.get(null);
				sb.append("now=").append(com.shatteredpixel.shatteredpixeldungeon.actors.Actor.now())
						.append(" current=").append(c == null ? "null" : c.getClass().getSimpleName() + (c instanceof Hero ? "P" + (Coop.indexOf((Hero)c)+1) : ""));
				java.util.Set<?> set = (java.util.Set<?>) all.get(null);
				for (Hero h : Coop.heroes()){
					sb.append(" | P").append(Coop.indexOf(h)+1).append(" inActors=").append(set.contains(h))
							.append(" time=").append(time.get(h))
							.append(" paralysed=").append(h.paralysed)
							.append(" spriteMoving=").append(h.sprite != null && h.sprite.isMoving);
				}
			} catch (Exception e){
				sb.append("dump failed ").append(e);
			}
			//where the game logic thread is stuck
			for (java.util.Map.Entry<Thread, StackTraceElement[]> t : Thread.getAllStackTraces().entrySet()){
				if (!t.getKey().getName().contains("Actor")) continue;
				sb.append(" | actor thread ").append(t.getKey().getState()).append(":");
				StackTraceElement[] st = t.getValue();
				for (int i = 0; i < Math.min(18, st.length); i++) sb.append(" <- ").append(st[i]);
			}
			return sb.toString();
		}

		static String followedHero(){
			for (Hero h : Coop.heroes()) if (h.sprite != null && Camera.main.followTarget() == h.sprite) return "P" + (Coop.indexOf(h)+1);
			return "-";
		}

		static String shownHero(){
			try {
				java.lang.reflect.Field f = com.shatteredpixel.shatteredpixeldungeon.coop.CoopUI.class.getDeclaredField("shownHero");
				f.setAccessible(true);
				Object o = f.get(null);
				return o == null ? "null" : "P" + (Coop.indexOf((Hero) o)+1);
			} catch (Exception e){
				return "?";
			}
		}

		static int lastProgressActions = 0;
		static int progressDepth = 0;

		static String reachability(){
			Level level = Dungeon.level;
			LevelTransition ex = level.getTransition( LevelTransition.Type.REGULAR_EXIT );
			if (ex == null) return "no exit";
			boolean[] pass = com.watabou.utils.BArray.or( level.passable, level.avoid, null );
			PathFinder.buildDistanceMap( ex.cell(), pass );
			StringBuilder sb = new StringBuilder("reach:");
			for (Hero h : Coop.aliveHeroes()){
				sb.append(" P").append(Coop.indexOf(h)+1).append("=").append(PathFinder.distance[h.pos] == Integer.MAX_VALUE ? "unreachable" : PathFinder.distance[h.pos]);
			}
			sb.append(" exitTerrain=").append(level.map[ex.cell()]);
			for (int i : PathFinder.NEIGHBOURS8){
				Object c = com.shatteredpixel.shatteredpixeldungeon.actors.Actor.findChar(ex.cell()+i);
				if (c != null) sb.append(" nearExit:").append(c.getClass().getSimpleName()).append("@").append(ex.cell()+i);
			}
			Object onExit = com.shatteredpixel.shatteredpixeldungeon.actors.Actor.findChar(ex.cell());
			if (onExit != null) sb.append(" onExit:").append(onExit.getClass().getSimpleName());
			return sb.toString();
		}

		static String state(){
			StringBuilder sb = new StringBuilder();
			sb.append("depth=").append(Dungeon.depth).append(" actions=").append(actions)
					.append(" active=P").append(Coop.indexOf(Dungeon.hero)+1)
					.append(" actorProcessing=").append(com.shatteredpixel.shatteredpixeldungeon.actors.Actor.processing())
					.append(" focus=P").append(com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime.focus() == null ? 0 : Coop.indexOf(com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime.focus())+1);
			for (Hero x : Coop.heroes()) sb.append(" t").append(Coop.indexOf(x)+1).append("=").append(com.shatteredpixel.shatteredpixeldungeon.actors.Actor.coopTime(x))
					.append(com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime.sameGroup(x, com.shatteredpixel.shatteredpixeldungeon.coop.CoopTime.focus()) ? "(f)" : "");
			if (Dungeon.level != null){
				LevelTransition ex = Dungeon.level.getTransition( LevelTransition.Type.REGULAR_EXIT );
				sb.append(" locked=").append(Dungeon.level.locked);
				if (ex != null){
					sb.append(" exit=").append(ex.cell());
					for (Hero h : Coop.aliveHeroes()) sb.append(" distP").append(Coop.indexOf(h)+1).append("=").append(Dungeon.level.distance(h.pos, ex.cell()));
				}
			}
			for (Hero h : Coop.heroes()){
				sb.append(" | P").append(Coop.indexOf(h)+1).append(" pos=").append(h.pos)
						.append(" hp=").append(h.HP).append("/").append(h.HT)
						.append(" ready=").append(h.ready)
						.append(" action=").append(h.curAction == null ? "-" : h.curAction.getClass().getSimpleName())
						.append(" dead=").append(Coop.isDead(h));
				if (h.sprite != null) sb.append(" vis=").append(h.sprite.visible).append(" inScene=").append(h.sprite.parent != null);
			}
			return sb.toString();
		}
	}
}
