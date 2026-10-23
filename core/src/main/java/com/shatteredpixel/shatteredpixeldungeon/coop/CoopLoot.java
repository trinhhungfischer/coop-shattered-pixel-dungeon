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
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.Stylus;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfStrength;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfUpgrade;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.StoneOfEnchantment;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.StoneOfIntuition;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.TrinketCatalyst;
import com.watabou.utils.Bundle;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;

/**
 * Coop rule: a limited drop can be looted once by EACH hero.
 *
 * A "limited" item is an item of a limited class (see isLimitedClass) that was placed in a
 * plain heap while a level was being generated (Dungeon.level == null during generation).
 * Items dropped at runtime (by heroes, mobs, traps...) are never tagged, so a hero can't
 * duplicate an item by dropping it for a teammate.
 *
 * Each tagged item keeps a bitmask of the party indices (Coop.indexOf) that looted it.
 * When a hero picks a tagged item up, the hero keeps the picked item and a fresh copy is put
 * back in the heap, unless every alive hero has now looted it. Copies are made with
 * Item.duplicate(), so LimitedDrops counters (only touched at level generation) are not
 * double counted, and Item.collect still runs per hero (catalog, journal, etc.).
 *
 * Heaps are re-sorted per active hero so items that hero already looted sit at the bottom,
 * meaning Heap.peek() / pickUp() normally target something the active hero may still take.
 *
 * Nothing here does anything while Coop.active() is false.
 */
public class CoopLoot {

	//per heap record: tagged item -> bitmask of party indices that looted it
	public static class Record {
		public final IdentityHashMap<Item, Integer> looted = new IdentityHashMap<>();
		//quantity of each tagged item when it was tagged. Picking an item up can merge it into
		//an existing stack, which zeroes its quantity, so the copy needs this.
		public final IdentityHashMap<Item, Integer> quantity = new IdentityHashMap<>();

		void put( Item item, int mask ){
			looted.put(item, mask);
			quantity.put(item, item.quantity());
		}

		void remove( Item item ){
			looted.remove(item);
			quantity.remove(item);
		}

		Integer qtyOf( Item item ){
			return quantity.get(item);
		}
	}

	static {
		Coop.addListener(new Coop.Listener() {
			@Override
			public void onActiveHeroChanged(Hero previous, Hero current) {
				refreshLevel();
			}

			@Override
			public void onHeroDied(Hero hero) {
				refreshLevel();
			}

			@Override
			public void onPartyChanged() {
				refreshLevel();
			}
		});
	}

	//call once at startup if you want the listener registered before any heap code runs.
	//(Any Heap method that reaches CoopLoot also registers it.)
	public static void init(){
		//triggers the static initializer
	}

	//true for a party run, including the window where a new coop run is being set up
	private static boolean partyRun(){
		return Coop.active() || CoopSetup.pending();
	}

	public static boolean isLimitedClass( Item item ){
		if (item == null) return false;
		Class<?> c = item.getClass();
		return c == PotionOfStrength.class
				|| c == ScrollOfUpgrade.class
				|| c == Stylus.class
				|| c == StoneOfEnchantment.class
				|| c == StoneOfIntuition.class
				|| c == TrinketCatalyst.class;
	}

	public static boolean isTagged( Heap heap, Item item ){
		return heap.coopLoot != null && heap.coopLoot.looted.containsKey(item);
	}

	//a tagged item in a plain heap, in an active coop game
	public static boolean isLimited( Heap heap, Item item ){
		return Coop.active() && heap.type == Heap.Type.HEAP && isTagged(heap, item);
	}

	private static int mask( Heap heap, Item item ){
		Integer m = heap.coopLoot.looted.get(item);
		return m == null ? 0 : m;
	}

	private static int bit( Hero hero ){
		int i = Coop.indexOf(hero);
		return (i < 0 || i >= 31) ? 0 : (1 << i);
	}

	private static int aliveMask(){
		int m = 0;
		for (Hero h : Coop.aliveHeroes()) m |= bit(h);
		return m;
	}

	public static boolean hasLooted( Heap heap, Item item, Hero hero ){
		return isLimited(heap, item) && (mask(heap, item) & bit(hero)) != 0;
	}

	//true when the given hero may not pick up the top of the heap, because they already took
	//their share of it. Intended for Hero.actPickUp and similar pickup loops.
	public static boolean alreadyLooted( Heap heap, Hero hero ){
		if (!Coop.active() || heap == null || heap.isEmpty()) return false;
		return hasLooted(heap, heap.peek(), hero);
	}

	//every alive hero has looted the heap's top items, so they aren't worth protecting
	private static boolean lootedByAllAlive( Heap heap, Item item ){
		int alive = aliveMask();
		return (mask(heap, item) & alive) == alive;
	}

	//limited items some alive hero still hasn't looted survive fire, frost and explosions
	public static boolean protects( Heap heap, Item item ){
		return isLimited(heap, item) && !lootedByAllAlive(heap, item);
	}

	//items stacking onto a tagged item would get copied along with it, so don't merge them
	public static boolean blocksMerge( Heap heap, Item item ){
		return isTagged(heap, item);
	}

	//a tagged item that was just taken out of a heap without being looted, see onPickUp
	private static Item movedItem = null;
	private static int movedMask = 0;

	//called by Heap.drop after the item was placed in the heap
	public static void onDrop( Heap heap, Item item ){
		Item moved = movedItem;
		movedItem = null;
		if (item != null && item == moved){
			if (heap.coopLoot == null) heap.coopLoot = new Record();
			heap.coopLoot.put(item, movedMask);
			return;
		}
		//Dungeon.level is null only while a new level is being generated
		if (Dungeon.level != null || !partyRun() || !isLimitedClass(item)) return;
		if (heap.coopLoot == null) heap.coopLoot = new Record();
		if (!heap.coopLoot.looted.containsKey(item)) heap.coopLoot.put(item, 0);
	}

	/**
	 * Called by Heap.pickUp before the top item is removed.
	 * Returns the picked item when the heap keeps a copy for the heroes that haven't looted it
	 * yet, or null for the normal removal.
	 */
	public static Item onPickUp( Heap heap ){
		if (!Coop.active() || heap.isEmpty()) return null;
		Item item = heap.peek();
		if (!isLimited(heap, item)) return null;

		Hero hero = Dungeon.hero;
		//only the hero pickup path duplicates: the item was just collected by the active hero.
		//Traps, wells, knockback etc. move the item itself and still have it at full quantity.
		boolean collected = hero != null
				&& (item.quantity() <= 0 || hero.belongings.contains(item));
		if (!collected || bit(hero) == 0){
			//the item itself is being moved (trap, well, knockback...), its tag follows it
			movedItem = item;
			movedMask = mask(heap, item);
			heap.coopLoot.remove(item);
			return null;
		}

		int m = mask(heap, item);
		if ((m & bit(hero)) != 0){
			//Fallback when the caller didn't check alreadyLooted(): the hero takes it normally
			//and the remaining heroes lose this copy. Better than an endless pickup loop.
			heap.coopLoot.remove(item);
			return null;
		}
		m |= bit(hero);
		Integer qty = heap.coopLoot.qtyOf(item);
		heap.coopLoot.remove(item);

		int alive = aliveMask();
		if ((m & alive) == alive){
			return null;
		}

		Item copy = item.duplicate();
		if (copy == null){
			return null;
		}
		//merging into an existing stack zeroes the picked item's quantity
		if (copy.quantity() <= 0) copy.quantity(qty != null && qty > 0 ? qty : 1);

		heap.items.set(0, copy);
		heap.coopLoot.put(copy, m);
		sortFor(heap, hero);
		return item;
	}

	//moves items the given hero already looted to the bottom, keeping the rest in order
	public static void sortFor( Heap heap, Hero hero ){
		if (!Coop.active() || heap.coopLoot == null || heap.coopLoot.looted.isEmpty()) return;
		ArrayList<Item> mine = new ArrayList<>();
		LinkedList<Item> rest = new LinkedList<>();
		for (Item i : heap.items){
			if (hasLooted(heap, i, hero)) mine.add(i);
			else rest.add(i);
		}
		if (mine.isEmpty()) return;
		rest.addAll(mine);
		heap.items.clear();
		heap.items.addAll(rest);
	}

	//true when everything left in the heap was already looted by the active hero
	public static boolean lootedForActive( Heap heap ){
		if (!Coop.active() || heap.coopLoot == null || heap.isEmpty()) return false;
		for (Item i : heap.items){
			if (!hasLooted(heap, i, Dungeon.hero)) return false;
		}
		return true;
	}

	//sprite alpha for a heap; 1 normally, faded when the active hero already took everything
	public static float heapAlpha( Heap heap ){
		return lootedForActive(heap) ? 0.35f : 1f;
	}

	//re-sorts and redraws every heap of the current level for the active hero, and removes
	//tagged items that every alive hero has looted (e.g. after a teammate died).
	public static void refreshLevel(){
		if (!Coop.active() || Dungeon.level == null || Dungeon.hero == null) return;
		for (Heap heap : Dungeon.level.heaps.valueList()){
			if (heap.coopLoot == null || heap.coopLoot.looted.isEmpty()) continue;
			if (heap.type == Heap.Type.HEAP) {
				for (Item i : heap.items.toArray(new Item[0])) {
					if (isTagged(heap, i) && lootedByAllAlive(heap, i)) {
						heap.coopLoot.remove(i);
						heap.items.remove(i);
					}
				}
				if (heap.isEmpty()) {
					heap.destroy();
					continue;
				}
				sortFor(heap, Dungeon.hero);
			}
			if (heap.sprite != null){
				heap.sprite.view(heap).place(heap.pos);
			}
		}
	}

	private static final String COOP_LIMITED = "coop_limited";
	private static final String COOP_LOOTED  = "coop_looted";

	public static void store( Heap heap, Bundle bundle ){
		if (heap.coopLoot == null || heap.coopLoot.looted.isEmpty()) return;
		List<Integer> idx = new ArrayList<>();
		List<Integer> masks = new ArrayList<>();
		for (int i = 0; i < heap.items.size(); i++){
			Item item = heap.items.get(i);
			Integer m = heap.coopLoot.looted.get(item);
			if (m != null){
				idx.add(i);
				masks.add(m);
			}
		}
		if (idx.isEmpty()) return;
		int[] a = new int[idx.size()];
		int[] b = new int[idx.size()];
		for (int i = 0; i < a.length; i++){
			a[i] = idx.get(i);
			b[i] = masks.get(i);
		}
		bundle.put( COOP_LIMITED, a );
		bundle.put( COOP_LOOTED, b );
	}

	//call after heap.items was restored, before any item is removed from it
	public static void restore( Heap heap, Bundle bundle, List<Item> restoredItems ){
		if (!bundle.contains(COOP_LIMITED)) return;
		int[] a = bundle.getIntArray(COOP_LIMITED);
		int[] b = bundle.getIntArray(COOP_LOOTED);
		if (a == null || b == null) return;
		for (int i = 0; i < a.length && i < b.length; i++){
			if (a[i] >= 0 && a[i] < restoredItems.size() && restoredItems.get(a[i]) != null){
				if (heap.coopLoot == null) heap.coopLoot = new Record();
				heap.coopLoot.put(restoredItems.get(a[i]), b[i]);
			}
		}
	}
}
