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
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Blacksmith;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Wandmaker;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.CorpseDust;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.DarkGold;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.Embers;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.Pickaxe;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.plants.Rotberry;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndWandmaker;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

/**
 * Coop rule for quest rewards: each alive hero gets their own reward once.
 *
 * Ghost / Wandmaker: the NPC stays after a hero picks a reward; the picked reward slot is
 *   refilled with a fresh copy (made before enchant/identify) until every alive hero claimed.
 *   For the Wandmaker the quest item is turned in only once; later heroes see a stand-in
 *   quest item that isn't in their inventory.
 * Blacksmith: every hero gets a pickaxe; on completion all alive heroes' dark gold counts
 *   towards favor, and each alive hero gets their own copy of the whole reward state
 *   (favor, pickaxe, smith rewards, cost counters). The live Blacksmith.Quest fields always
 *   hold the state of one hero (smithOwner) and are swapped when another hero talks to the smith.
 * Imp: not handled (see report): the vault quest is built around a single hero's inventory.
 *
 * Claims are bitmasks of party indices (Coop.indexOf), persisted inside each quest's bundle.
 */
public class CoopQuests {

	public static final int GHOST       = 0;
	public static final int WANDMAKER   = 1;
	public static final int IMP         = 2;
	public static final int BLACKSMITH  = 3;

	private static final int[] claimed = new int[4];

	private static int bit( Hero h ){
		int i = Coop.indexOf(h);
		return (i < 0 || i >= 31) ? 0 : (1 << i);
	}

	private static int aliveMask(){
		int m = 0;
		for (Hero h : Coop.aliveHeroes()) m |= bit(h);
		return m;
	}

	public static void reset( int quest ){
		claimed[quest] = 0;
		if (quest == BLACKSMITH){
			smithStates.clear();
			smithOwner = -1;
		}
	}

	public static boolean hasClaimed( int quest, Hero h ){
		return Coop.active() && (claimed[quest] & bit(h)) != 0;
	}

	public static boolean anyClaimed( int quest ){
		return Coop.active() && claimed[quest] != 0;
	}

	//a fresh copy of a reward, taken before it is enchanted/identified/picked up. Null in solo.
	public static Item copyReward( Item reward ){
		if (!Coop.active() || reward == null) return null;
		return reward.duplicate();
	}

	//records the claim, returns true when the quest NPC must stay for heroes that haven't claimed
	public static boolean claimAndStay( int quest, Hero h ){
		if (!Coop.active()) return false;
		claimed[quest] |= bit(h);
		int alive = aliveMask();
		return (claimed[quest] & alive) != alive;
	}

	private static final String CLAIMED = "coop_claimed";

	public static void store( Bundle node, int quest ){
		if (claimed[quest] != 0) node.put( CLAIMED, claimed[quest] );
	}

	public static void restore( Bundle node, int quest ){
		claimed[quest] = node.contains(CLAIMED) ? node.getInt(CLAIMED) : 0;
	}

	//*** Wandmaker ***

	//handles the wandmaker for heroes after the first claim. Returns true if the interaction is handled.
	public static boolean wandmakerInteract( Wandmaker wandmaker, int type ){
		if (!anyClaimed(WANDMAKER)) return false;
		Hero hero = Dungeon.hero;
		if (hasClaimed(WANDMAKER, hero)){
			wandmaker.yell( Messages.get(WndWandmaker.class, "farewell", Messages.titleCase(hero.name())) );
			return true;
		}
		//the quest item was already turned in by a teammate, show a stand-in
		final Item token;
		switch (type){
			case 1: default: token = new CorpseDust(); break;
			case 2: token = new Embers(); break;
			case 3: token = new Rotberry.Seed(); break;
		}
		Game.runOnRenderThread(new Callback() {
			@Override
			public void call() {
				GameScene.show( new WndWandmaker( wandmaker, token ) );
			}
		});
		return true;
	}

	//*** Blacksmith ***

	//party index -> reward state of that hero. The live Blacksmith.Quest fields belong to smithOwner.
	private static final HashMap<Integer, Bundle> smithStates = new HashMap<>();
	private static int smithOwner = -1;

	//gives a pickaxe to every other alive hero when the quest is accepted
	public static void giveExtraPickaxes(){
		if (!Coop.active()) return;
		for (Hero h : Coop.aliveHeroes()){
			if (h == Dungeon.hero || h.belongings.getItem(Pickaxe.class) != null) continue;
			Item pick = new Pickaxe().identify(false);
			if (!pick.collect(h.belongings.backpack)){
				Dungeon.level.drop( pick, h.pos ).sprite.drop();
			}
		}
	}

	//called at the end of Blacksmith.Quest.complete()
	public static void onBlacksmithComplete(){
		smithStates.clear();
		smithOwner = -1;
		if (!Coop.active()) return;

		//teammates' dark gold also counts, still capped like solo
		int othersGold = 0;
		for (Hero h : Coop.aliveHeroes()){
			if (h == Dungeon.hero) continue;
			DarkGold gold = h.belongings.getItem(DarkGold.class);
			if (gold != null){
				othersGold += gold.quantity();
				gold.detachAll(h.belongings.backpack);
			}
			Pickaxe pick = h.belongings.getItem(Pickaxe.class);
			if (pick != null){
				if (pick.isEquipped(h)){
					boolean wasCursed = pick.cursed;
					pick.cursed = false;
					pick.doUnequip(h, false);
					pick.cursed = wasCursed;
				}
				pick.detach(h.belongings.backpack);
			}
		}
		int goldFavor = Blacksmith.Quest.favor - (Blacksmith.Quest.bossBeaten() ? 1000 : 0);
		int newGoldFavor = Math.min(2000, goldFavor + othersGold*50);
		int delta = Math.max(0, newGoldFavor - goldFavor);
		Blacksmith.Quest.favor += delta;
		Statistics.questScores[2] += delta;
		if (Blacksmith.Quest.favor >= 2500){
			Blacksmith.Quest.freePickaxe = true;
		}

		//everyone alive gets the same starting reward state
		smithOwner = Coop.indexOf(Dungeon.hero);
		Bundle state = captureSmith();
		for (Hero h : Coop.aliveHeroes()){
			int i = Coop.indexOf(h);
			if (i != smithOwner) smithStates.put(i, state);
		}
	}

	//puts the given hero's reward state into the live Blacksmith.Quest fields
	public static void syncBlacksmith( Hero hero ){
		if (!Coop.active() || smithOwner < 0) return;
		int idx = Coop.indexOf(hero);
		if (idx < 0 || idx == smithOwner) return;
		smithStates.put(smithOwner, captureSmith());
		Bundle mine = smithStates.remove(idx);
		loadSmith(mine);
		smithOwner = idx;
	}

	//true if any hero still has blacksmith rewards to spend
	public static boolean blacksmithRewardsLeft(){
		if (Blacksmith.Quest.rewardsAvailable()) return true;
		if (!Coop.active()) return false;
		for (Bundle b : smithStates.values()){
			if (b.getInt(S_FAVOR) > 0
					|| (b.contains(S_REWARDS) && b.getInt(S_SMITHS) > 0)
					|| (b.contains(S_PICKAXE) && b.getBoolean(S_FREE_PICK))){
				return true;
			}
		}
		return false;
	}

	private static final String S_FAVOR     = "favor";
	private static final String S_PICKAXE   = "pickaxe";
	private static final String S_FREE_PICK = "free_pickaxe";
	private static final String S_REFORGES  = "reforges";
	private static final String S_HARDENS   = "hardens";
	private static final String S_UPGRADES  = "upgrades";
	private static final String S_SMITHS    = "smiths";
	private static final String S_REWARDS   = "smith_rewards";

	private static Bundle captureSmith(){
		Bundle b = new Bundle();
		b.put(S_FAVOR, Blacksmith.Quest.favor);
		if (Blacksmith.Quest.pickaxe != null) b.put(S_PICKAXE, Blacksmith.Quest.pickaxe);
		b.put(S_FREE_PICK, Blacksmith.Quest.freePickaxe);
		b.put(S_REFORGES, Blacksmith.Quest.reforges);
		b.put(S_HARDENS, Blacksmith.Quest.hardens);
		b.put(S_UPGRADES, Blacksmith.Quest.upgrades);
		b.put(S_SMITHS, Blacksmith.Quest.smiths);
		if (Blacksmith.Quest.smithRewards != null) b.put(S_REWARDS, Blacksmith.Quest.smithRewards);
		//items are serialized right away, every read builds fresh item objects
		return b;
	}

	@SuppressWarnings("unchecked")
	private static void loadSmith( Bundle b ){
		if (b == null){
			//a hero with no reward state (e.g. dead when the quest was completed)
			Blacksmith.Quest.favor = 0;
			Blacksmith.Quest.pickaxe = null;
			Blacksmith.Quest.freePickaxe = false;
			Blacksmith.Quest.smiths = 0;
			Blacksmith.Quest.smithRewards = null;
			return;
		}
		Blacksmith.Quest.favor = b.getInt(S_FAVOR);
		Blacksmith.Quest.pickaxe = b.contains(S_PICKAXE) ? (Item) b.get(S_PICKAXE) : null;
		Blacksmith.Quest.freePickaxe = b.getBoolean(S_FREE_PICK);
		Blacksmith.Quest.reforges = b.getInt(S_REFORGES);
		Blacksmith.Quest.hardens = b.getInt(S_HARDENS);
		Blacksmith.Quest.upgrades = b.getInt(S_UPGRADES);
		Blacksmith.Quest.smiths = b.getInt(S_SMITHS);
		if (b.contains(S_REWARDS)){
			Blacksmith.Quest.smithRewards = new ArrayList<>((Collection<Item>) ((Collection<?>) b.getCollection(S_REWARDS)));
		} else {
			Blacksmith.Quest.smithRewards = null;
		}
	}

	private static final String SMITH_OWNER = "coop_smith_owner";
	private static final String SMITH_STATE = "coop_smith_state_";

	public static void storeBlacksmith( Bundle node ){
		store(node, BLACKSMITH);
		if (smithOwner < 0) return;
		node.put(SMITH_OWNER, smithOwner);
		for (Integer i : smithStates.keySet()){
			node.put(SMITH_STATE + i, smithStates.get(i));
		}
	}

	public static void restoreBlacksmith( Bundle node ){
		restore(node, BLACKSMITH);
		smithStates.clear();
		smithOwner = node.contains(SMITH_OWNER) ? node.getInt(SMITH_OWNER) : -1;
		if (smithOwner < 0) return;
		for (int i = 0; i < 31; i++){
			if (node.contains(SMITH_STATE + i)){
				smithStates.put(i, node.getBundle(SMITH_STATE + i));
			}
		}
	}
}
