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

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Belongings;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;

import java.util.ArrayList;
import java.util.List;

/**
 * Naming a hero's items the same way on the host and on a client. Equipped slots are
 * w (weapon), a (armor), f (artifact), m (misc), r (ring), s (second weapon). Items in bags are
 * named by what they are, not where: "i:<class>|<level>|<quantity>|<n>" is the n-th such item.
 * Bag order can differ between the host and a client's mirror (restoring a backpack re-sorts
 * and merges items), so positions would not match.
 */
public class CoopItems {

	public static String pathOf( Hero h, Item item ){
		if (h == null || item == null) return null;
		Belongings b = h.belongings;
		if (item == b.weapon)    return "w";
		if (item == b.armor)     return "a";
		if (item == b.artifact)  return "f";
		if (item == b.misc)      return "m";
		if (item == b.ring)      return "r";
		if (item == b.secondWep) return "s";
		String sig = signature( item );
		int n = 0;
		for (Item i : bagItems( h )){
			if (i == item) return "i:" + sig + "|" + n;
			if (signature( i ).equals( sig )) n++;
		}
		return null;
	}

	private static String signature( Item item ){
		return item.getClass().getName() + "|" + item.level() + "|" + item.quantity();
	}

	public static Item at( Hero h, String path ){
		if (h == null || path == null || path.isEmpty()) return null;
		Belongings b = h.belongings;
		switch (path){
			case "w": return b.weapon;
			case "a": return b.armor;
			case "f": return b.artifact;
			case "m": return b.misc;
			case "r": return b.ring;
			case "s": return b.secondWep;
		}
		if (!path.startsWith( "i:" )) return null;
		String rest = path.substring( 2 );
		int bar = rest.lastIndexOf( '|' );
		if (bar < 0) return null;
		String sig = rest.substring( 0, bar );
		int n;
		try {
			n = Integer.parseInt( rest.substring( bar + 1 ) );
		} catch (NumberFormatException e){
			return null;
		}
		int seen = 0;
		for (Item i : bagItems( h )){
			if (signature( i ).equals( sig )){
				if (seen == n) return i;
				seen++;
			}
		}
		return null;
	}

	//items in bags, bags included, in bag order
	private static List<Item> bagItems( Hero h ){
		ArrayList<Item> list = new ArrayList<>();
		collect( h.belongings.backpack, list );
		return list;
	}

	//every item a hero has: equipped ones and everything in bags (bags included)
	public static List<Item> all( Hero h ){
		ArrayList<Item> list = new ArrayList<>();
		Belongings b = h.belongings;
		for (Item i : new Item[]{ b.weapon, b.armor, b.artifact, b.misc, b.ring, b.secondWep }){
			if (i != null) list.add( i );
		}
		collect( b.backpack, list );
		return list;
	}

	private static void collect( Bag bag, List<Item> list ){
		for (Item i : bag.items){
			list.add( i );
			if (i instanceof Bag) collect( (Bag) i, list );
		}
	}
}
