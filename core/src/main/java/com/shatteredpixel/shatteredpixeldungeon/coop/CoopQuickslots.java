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
import com.shatteredpixel.shatteredpixeldungeon.QuickSlot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.watabou.utils.Bundle;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * One QuickSlot per party hero. Dungeon.quickslot always points at the active hero's QuickSlot,
 * the others are kept here and swapped in when the active hero changes.
 *
 * Saving: items store their own quickslot position by looking at Dungeon.quickslot, which only
 * works for one hero. So the primary hero is saved the normal way (Dungeon.quickslot must be the
 * primary's while Dungeon stores HERO, see beforeSave), and the other heroes' slots are stored
 * here by item index inside their belongings.
 *
 * All methods do nothing in solo play.
 */
public class CoopQuickslots {

	private static final IdentityHashMap<Hero, QuickSlot> slots = new IdentityHashMap<>();
	//the hero whose QuickSlot is currently Dungeon.quickslot
	private static Hero owner = null;

	//makes Dungeon.quickslot the given hero's QuickSlot, creating an empty one if needed.
	public static synchronized void use( Hero h ){
		if (h == null) return;
		if (owner == null) owner = Coop.primary();
		if (h == owner) return;
		if (owner != null) slots.put(owner, Dungeon.quickslot);
		QuickSlot q = slots.get(h);
		if (q == null){
			q = new QuickSlot();
			slots.put(h, q);
		}
		Dungeon.quickslot = q;
		owner = h;
	}

	public static synchronized QuickSlot of( Hero h ){
		if (h == owner || (owner == null && h == Coop.primary())) return Dungeon.quickslot;
		return slots.get(h);
	}

	//for new runs: call before HeroClass.initHero( extraHero ) so its starting items get their own quickslots,
	//and endHeroInit() after the last extra hero has been initialized.
	//Dungeon.hero must be the new primary hero (already initialized) while these are called.
	public static synchronized void beginHeroInit( Hero extra ){
		if (owner != Dungeon.hero && !slots.containsKey(Dungeon.hero)){
			//first extra hero of a new run: forget everything from earlier runs
			slots.clear();
			owner = Dungeon.hero;
		}
		use( extra );
	}

	public static synchronized void endHeroInit(){
		use( Dungeon.hero );
	}

	static synchronized void onActiveHeroChanged( Hero current ){
		if (!Coop.active()) return;
		use( current );
	}

	static synchronized void onPartyChanged(){
		if (!Coop.active()){
			slots.clear();
			owner = null;
			return;
		}

		List<Hero> party = Coop.heroes();

		//drop entries from an earlier run
		boolean stale = owner != null && !party.contains(owner);
		for (Hero h : slots.keySet()){
			if (!party.contains(h)) stale = true;
		}
		if (stale){
			slots.clear();
			owner = null;
		}
		if (owner == null) owner = Coop.primary();

		//fallback for heroes that were initialized while sharing one QuickSlot:
		//split the shared slots by item ownership.
		QuickSlot shared = Dungeon.quickslot;
		for (Hero h : party){
			if (h == owner || slots.containsKey(h)) continue;
			QuickSlot q = new QuickSlot();
			for (int i = 0; i < QuickSlot.SIZE; i++){
				Item item = shared.getItem(i);
				if (item != null && !shared.isPlaceholder(i) && owns(h, item)){
					shared.clearSlot(i);
					q.setSlot(i, item);
				}
			}
			slots.put(h, q);
		}

		use( Dungeon.hero );
	}

	private static boolean owns( Hero h, Item item ){
		for (Item i : h.belongings){
			if (i == item) return true;
		}
		return false;
	}

	private static Hero savedOwner = null;

	//call in Dungeon.saveGame before storing HERO: makes Dungeon.quickslot the primary hero's.
	public static synchronized void beforeSave(){
		if (!Coop.active()) return;
		savedOwner = owner;
		use( Coop.primary() );
	}

	//call in Dungeon.saveGame after quickslot.storePlaceholders(): restores the active hero's quickslot.
	public static synchronized void afterSave(){
		if (!Coop.active()) return;
		if (savedOwner != null) use( savedOwner );
		savedOwner = null;
	}

	private static final String COOP_QUICKSLOTS = "coop_quickslots";
	private static final String ITEM_INDEX      = "item_index";

	//stores the non-primary heroes' quickslots. Call anywhere in Dungeon.saveGame.
	public static synchronized void store( Bundle bundle ){
		if (!Coop.active()) return;
		ArrayList<Bundle> all = new ArrayList<>();
		List<Hero> party = Coop.heroes();
		for (int p = 1; p < party.size(); p++){
			Hero h = party.get(p);
			QuickSlot q = of(h);
			Bundle b = new Bundle();
			int[] index = new int[QuickSlot.SIZE];
			for (int i = 0; i < QuickSlot.SIZE; i++){
				index[i] = -1;
				if (q == null || q.getItem(i) == null || q.isPlaceholder(i)) continue;
				int n = 0;
				for (Item it : h.belongings){
					if (it == q.getItem(i)){
						index[i] = n;
						break;
					}
					n++;
				}
			}
			b.put( ITEM_INDEX, index );
			if (q != null) q.storePlaceholders( b );
			all.add(b);
		}
		Bundle wrapper = new Bundle();
		for (int i = 0; i < all.size(); i++){
			wrapper.put( Integer.toString(i+1), all.get(i) );
		}
		bundle.put( COOP_QUICKSLOTS, wrapper );
	}

	//call in Dungeon.loadGame after Coop.restore(). At that point Dungeon.quickslot holds the primary's slots.
	public static synchronized void restore( Bundle bundle ){
		//a listener may already have swapped quickslots during Coop.restore, find the primary's again
		QuickSlot primary = of( Coop.primary() );
		if (primary == null) primary = Dungeon.quickslot;
		slots.clear();
		owner = Coop.primary();
		Dungeon.quickslot = primary;
		if (!Coop.active() || !bundle.contains(COOP_QUICKSLOTS)) {
			if (Coop.active()) onPartyChanged();
			return;
		}
		Bundle wrapper = bundle.getBundle( COOP_QUICKSLOTS );
		List<Hero> party = Coop.heroes();
		for (int p = 1; p < party.size(); p++){
			Hero h = party.get(p);
			QuickSlot q = new QuickSlot();
			String key = Integer.toString(p);
			if (wrapper.contains(key)){
				Bundle b = wrapper.getBundle(key);
				q.restorePlaceholders( b );
				int[] index = b.getIntArray( ITEM_INDEX );
				if (index != null){
					ArrayList<Item> items = new ArrayList<>();
					for (Item it : h.belongings) items.add(it);
					for (int i = 0; i < index.length && i < QuickSlot.SIZE; i++){
						if (index[i] >= 0 && index[i] < items.size()){
							q.setSlot(i, items.get(index[i]));
						}
					}
				}
			}
			slots.put(h, q);
		}
		use( Dungeon.hero );
	}
}
