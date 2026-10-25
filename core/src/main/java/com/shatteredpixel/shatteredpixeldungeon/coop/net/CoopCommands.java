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
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.coop.Coop;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.Recipe;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.AlchemistsToolkit;
import com.shatteredpixel.shatteredpixeldungeon.scenes.AlchemyScene;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndEnergizeItem;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Online commands besides moving and using items (docs/coop-online-design.md, M5): talents,
 * alchemy and energizing items. A client's UI calls these instead of changing its mirror; the
 * host checks the command and applies it to the real hero.
 *
 * client -> host
 *  talent {name}                    spend a talent point
 *  alchemy {cls, lvl, qty, slot}    brew recipe number slot from these ingredients
 *  energize {path, all}             turn an item (or one of a stack) into alchemical energy
 * host -> client
 *  alchopen {kit}                   the hero reached an alchemy pot (or used a toolkit): open the
 *                                   alchemy screen there
 */
public class CoopCommands {

	public static final String TALENT    = "talent";
	public static final String ALCHEMY   = "alchemy";
	public static final String ENERGIZE  = "energize";
	public static final String ALCH_OPEN = "alchopen";

	// ---------------------------------------------------------------------------------
	// Talents
	// ---------------------------------------------------------------------------------

	//TalentButton: spending a point
	public static void upgradeTalent( Hero hero, Talent talent ){
		if (CoopRemote.applyingCommand() || !CoopRemote.online()){
			hero.upgradeTalent( talent );
			return;
		}
		if (CoopRemote.onlineClient()){
			Bundle m = NetConnection.message( TALENT );
			m.put( "name", talent.name() );
			CoopOnline.client.send( m );
			//the mirror shows the point at once (without its effects); the host's hero confirms it
			for (LinkedHashMap<Talent, Integer> tier : hero.talents){
				if (tier.containsKey( talent )) tier.put( talent, tier.get( talent ) + 1 );
			}
			return;
		}
		CoopRemote.hostTask( 0, () -> upgradeChecked( Coop.get( 0 ), talent ) );
	}

	static void upgradeChecked( Hero h, Talent talent ){
		for (int tier = 1; tier <= h.talents.size(); tier++){
			LinkedHashMap<Talent, Integer> points = h.talents.get( tier - 1 );
			if (!points.containsKey( talent )) continue;
			if (h.talentPointsAvailable( tier ) > 0 && points.get( talent ) < talent.maxPoints()){
				h.upgradeTalent( talent );
			}
			return;
		}
	}

	static void onTalent( Hero h, Bundle m ){
		try {
			upgradeChecked( h, Talent.valueOf( m.getString( "name" ) ) );
		} catch (IllegalArgumentException e){
			//not a talent of this version
		}
	}

	// ---------------------------------------------------------------------------------
	// Alchemy
	// ---------------------------------------------------------------------------------

	//heroes whose player has the alchemy screen open, with the toolkit it was opened with (if any)
	private static final IdentityHashMap<Hero, Object> alchemyOpen = new IdentityHashMap<>();
	private static final Object NO_KIT = new Object();

	//Hero.actAlchemy, the alchemist's toolkit, spirit form: a remote hero's alchemy screen opens on
	//its player's machine instead of switching the host's scene. Returns true if it did.
	public static boolean openAlchemy( Hero hero, AlchemistsToolkit kit ){
		if (!CoopRemote.onlineHost()) return false;
		if (hero == CoopRemote.localHero()){
			//the host's own alchemy screen pauses the game for everyone
			Bundle notice = NetConnection.message( "notice" );
			notice.put( "text", com.shatteredpixel.shatteredpixeldungeon.messages.Messages.get( CoopRemote.class, "host_alchemy" ) );
			CoopOnline.host.broadcast( notice );
			return false;
		}
		alchemyOpen.put( hero, kit == null ? NO_KIT : kit );
		Bundle m = NetConnection.message( ALCH_OPEN );
		m.put( "kit", kit != null );
		CoopRemote.sendToHero( hero, m );
		return true;
	}

	//a hero's next command ends its alchemy session
	static void closeAlchemy( Hero h ){
		alchemyOpen.remove( h );
	}

	static void clear(){
		alchemyOpen.clear();
	}

	//client: the alchemy screen on the mirror
	static void showAlchemy( Bundle m ){
		Hero me = CoopRemote.localHero();
		if (m.getBoolean( "kit" ) && me.belongings.artifact instanceof AlchemistsToolkit){
			AlchemyScene.assignToolkit( (AlchemistsToolkit) me.belongings.artifact );
		} else {
			AlchemyScene.clearToolkit();
		}
		ShatteredPixelDungeon.switchScene( AlchemyScene.class );
	}

	//AlchemyScene.combine on a client: the host brews too. The client goes on brewing on its
	//mirror to show the result; the host's hero replaces the mirror's belongings afterwards.
	public static void alchemyCombine( List<Item> ingredients, int slot ){
		if (!CoopRemote.onlineClient()) return;
		int n = ingredients.size();
		String[] cls = new String[n];
		int[] lvl = new int[n], qty = new int[n];
		for (int i = 0; i < n; i++){
			Item it = ingredients.get( i );
			cls[i] = it.getClass().getName();
			lvl[i] = it.level();
			qty[i] = it.quantity();
		}
		Bundle m = NetConnection.message( ALCHEMY );
		m.put( "cls", cls );
		m.put( "lvl", lvl );
		m.put( "qty", qty );
		m.put( "slot", slot );
		CoopOnline.client.send( m );
	}

	//host: what happened to the last alchemy command (tests and debugging)
	public static volatile String lastAlchemyResult = "";

	static void onAlchemy( Hero h, Bundle m ){
		Object open = alchemyOpen.get( h );
		if (open == null){
			lastAlchemyResult = "alchemy screen not open";
			return;
		}
		AlchemistsToolkit kit = open instanceof AlchemistsToolkit ? (AlchemistsToolkit) open : null;
		String[] cls = m.getStringArray( "cls" );
		int[] lvl = m.getIntArray( "lvl" ), qty = m.getIntArray( "qty" );
		if (cls == null || lvl == null || qty == null || cls.length != lvl.length || cls.length != qty.length) return;

		ArrayList<Item> ingredients = new ArrayList<>();
		for (int i = 0; i < cls.length; i++){
			Item found = null;
			for (Item it : CoopItems.bagItems( h )){
				if (it.getClass().getName().equals( cls[i] ) && it.level() == lvl[i] && it.quantity() >= qty[i] && qty[i] > 0){
					found = it;
					break;
				}
			}
			if (found == null){
				lastAlchemyResult = "missing ingredient " + cls[i];
				putBack( h, ingredients );
				return;
			}
			ingredients.add( found.quantity() > qty[i] ? found.split( qty[i] ) : found.detachAll( h.belongings.backpack ) );
		}

		ArrayList<Recipe> recipes = Recipe.findRecipes( ingredients );
		int slot = m.getInt( "slot" );
		if (slot < 0 || slot >= recipes.size()){
			lastAlchemyResult = "no recipe " + slot + " of " + recipes.size();
			putBack( h, ingredients );
			return;
		}
		Recipe recipe = recipes.get( slot );
		int cost = recipe.cost( ingredients );
		if (cost > Dungeon.energy + (kit == null ? 0 : kit.availableEnergy())){
			lastAlchemyResult = "not enough energy";
			putBack( h, ingredients );
			return;
		}
		if (kit != null) cost = kit.consumeEnergy( cost );
		Dungeon.energy -= cost;
		Item result = recipe.brew( ingredients );
		if (result != null){
			if (!result.collect( h.belongings.backpack )) Dungeon.level.drop( result, h.pos );
			Statistics.itemsCrafted++;
			lastAlchemyResult = "brewed " + result.getClass().getSimpleName();
		} else {
			lastAlchemyResult = "recipe gave nothing";
		}
		putBack( h, ingredients );
	}

	//ingredients not used up go back in the bag
	private static void putBack( Hero h, List<Item> items ){
		for (Item i : items){
			if (i.quantity() > 0 && !i.collect( h.belongings.backpack )) Dungeon.level.drop( i, h.pos );
		}
	}

	// ---------------------------------------------------------------------------------
	// Energizing
	// ---------------------------------------------------------------------------------

	//WndEnergizeItem.energizeAll / energizeOne. Returns true if the caller must stop: online it is a
	//command. A client in its alchemy screen still energizes its mirror, to show the energy.
	public static boolean energize( Item item, boolean all ){
		if (CoopRemote.applyingCommand() || !CoopRemote.online()) return false;
		boolean inAlchemy = Game.scene() instanceof AlchemyScene;
		if (CoopRemote.onlineClient()){
			String path = CoopItems.pathOf( CoopRemote.localHero(), item );
			if (path == null) return true;
			Bundle m = NetConnection.message( ENERGIZE );
			m.put( "path", path );
			m.put( "cls", item.getClass().getName() );
			m.put( "all", all );
			CoopOnline.client.send( m );
			return !inAlchemy;
		}
		//the host's own alchemy screen: the game is paused there, nothing else runs
		if (inAlchemy) return false;
		CoopRemote.hostTask( 0, () -> {
			if (all) WndEnergizeItem.energizeAll( item ); else WndEnergizeItem.energizeOne( item );
		});
		return true;
	}

	static void onEnergize( Hero h, Bundle m ){
		Item it = CoopItems.at( h, m.getString( "path" ) );
		if (it == null || !it.getClass().getName().equals( m.getString( "cls" ) ) || it.energyVal() <= 0) return;
		if (m.getBoolean( "all" )) WndEnergizeItem.energizeAll( it ); else WndEnergizeItem.energizeOne( it );
	}
}
