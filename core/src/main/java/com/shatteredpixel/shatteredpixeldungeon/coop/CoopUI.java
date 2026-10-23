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
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.ActionIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.InventoryPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.QuickSlotButton;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.Camera;
import com.watabou.noosa.tweeners.AlphaTweener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;

/**
 * Hotseat UI glue. Listens to Coop and keeps the game UI pointed at the active hero.
 *
 * Listener callbacks can arrive on the actor thread, so they only swap data (quickslots, action
 * indicator) and set flags. Everything that touches scene graph objects runs in update(), which
 * GameScene calls from the render thread.
 */
public class CoopUI implements Coop.Listener {

	private static final CoopUI INSTANCE = new CoopUI();

	public static void init(){
		Coop.addListener( INSTANCE );
		CoopVision.init();
	}

	//hero the UI and camera show: the hero whose input was last awaited. Another hero that keeps
	//walking on its own (travel, multi-turn actions) between those inputs doesn't take the view.
	private static volatile Hero shownHero = null;

	//sprites only move the camera for the hero the UI shows
	public static boolean hasCameraFocus( Hero h ){
		if (!Coop.active()) return true;
		Hero shown = shownHero;
		return shown == null ? h == Dungeon.hero : h == shown;
	}
	private static boolean needsObserve = false;
	private static volatile boolean partyDirty = true;
	private static final ArrayList<Hero> pendingDeaths = new ArrayList<>();

	//action indicator (combo, momentum, etc.) of the heroes that are not active right now
	private static final IdentityHashMap<Hero, ActionIndicator.Action> heroActions = new IdentityHashMap<>();

	@Override
	public void onActiveHeroChanged( Hero previous, Hero current ) {
		CoopQuickslots.onActiveHeroChanged( current );
		swapAction( previous, current );
		QuickSlotButton.lastTarget = null;
		InventoryPane.lastTarget = null;
		Item.updateQuickslot();
		BuffIndicator.refreshHero();
		partyDirty = true;
	}

	@Override
	public void onHeroDied( Hero hero ) {
		synchronized (CoopUI.class) {
			heroActions.remove( hero );
		}
		synchronized (pendingDeaths) {
			pendingDeaths.add( hero );
		}
		partyDirty = true;
	}

	@Override
	public void onPartyChanged() {
		synchronized (CoopUI.class) {
			heroActions.clear();
		}
		CoopQuickslots.onPartyChanged();
		shownHero = null;
		partyDirty = true;
	}

	private static synchronized void swapAction( Hero previous, Hero current ){
		ActionIndicator.Action a = ActionIndicator.action;
		if (previous != null && a instanceof Buff && ((Buff) a).target == previous){
			heroActions.put( previous, a );
		}
		ActionIndicator.Action next = heroActions.remove( current );
		if (next instanceof Buff && ((Buff) next).target != current) next = null;
		if (next != null) {
			ActionIndicator.setAction( next );
		} else {
			ActionIndicator.clearAction();
			ActionIndicator.refresh();
		}
	}

	//called every frame by GameScene.update, on the render thread
	public static void update( PartyPane pane ){
		if (!Coop.active() || Dungeon.hero == null) {
			if (pane != null) pane.visible = false;
			return;
		}

		//an action buff of a non-active hero grabbed the indicator (its buffs act on other turns)
		ActionIndicator.Action a = ActionIndicator.action;
		if (a instanceof Buff && ((Buff) a).target instanceof Hero && ((Buff) a).target != Dungeon.hero){
			synchronized (CoopUI.class) {
				heroActions.put( (Hero) ((Buff) a).target, a );
				ActionIndicator.clearAction( a );
				ActionIndicator.Action mine = heroActions.remove( Dungeon.hero );
				if (mine != null) ActionIndicator.setAction( mine );
				else ActionIndicator.refresh();
			}
		}

		ensureSprites();
		processDeaths();

		if (Dungeon.hero != shownHero && Dungeon.hero.ready){
			boolean first = shownHero == null;
			shownHero = Dungeon.hero;
			GameScene.coopRefreshHeroUI(); // status pane, key holds
			QuickSlotButton.cancel();
			InventoryPane.cancelTargeting();
			Item.updateQuickslot();
			ActionIndicator.refresh();
			needsObserve = true;
			partyDirty = true;

			if (Dungeon.hero.sprite != null) {
				//jump straight to the hero whose turn it is, instead of sweeping across the map
				Camera.main.snapTo( Dungeon.hero.sprite.center() );
				Camera.main.panFollow( Dungeon.hero.sprite, 5f );
				if (!first && Coop.aliveHeroes().size() > 1) {
					Dungeon.hero.sprite.showStatus( CharSprite.NEUTRAL,
							Messages.get(CoopUI.class, "turn_short", Coop.indexOf(Dungeon.hero)+1) );
				}
			}
		}

		//refresh fog/vision for the new hero once the actor thread is waiting on them
		if (needsObserve && Dungeon.hero.ready && Dungeon.level != null){
			needsObserve = false;
			Dungeon.observe();
		}

		if (partyDirty && pane != null){
			partyDirty = false;
			pane.rebuild();
		}
	}

	//every living party hero on this level gets a sprite, including heroes that arrive later.
	//GameScene.create calls this too, right after building the active hero's sprite.
	public static void ensureSprites(){
		if (!Coop.active()) return;
		HashSet<Char> chars = null;
		for (Hero h : Coop.aliveHeroes()){
			if (h.sprite != null && h.sprite.parent != null) continue;
			if (chars == null) chars = Actor.chars();
			if (!chars.contains( h )) continue;
			GameScene.addHeroSprite( h );
		}
	}

	private static void processDeaths(){
		ArrayList<Hero> deaths;
		synchronized (pendingDeaths) {
			if (pendingDeaths.isEmpty()) return;
			deaths = new ArrayList<>( pendingDeaths );
			pendingDeaths.clear();
		}
		for (Hero h : deaths){
			GLog.n( Messages.get(CoopUI.class, "died",
					Messages.titleCase(h.heroClass.title()), Coop.indexOf(h)+1) );

			//the spectator's body fades away, it no longer takes part in the game
			final CharSprite sprite = h.sprite;
			//when the whole party is gone, leave the last body for the regular game over flow
			if (sprite != null && sprite.parent != null && !Coop.allDead()) {
				sprite.die();
				sprite.parent.add( new AlphaTweener( sprite, 0, 1.5f ) {
					@Override
					protected void onComplete() {
						sprite.killAndErase();
					}
				} );
			}
		}
	}
}
