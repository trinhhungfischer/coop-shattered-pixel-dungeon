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
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Imp;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClothArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.DwarfToken;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.EscapeCrystal;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndImpOld;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndQuest;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;

import java.util.ArrayList;

/**
 * Coop rules for the Imp quest. Rule: every alive hero gets the Imp's reward once.
 *
 * New (vault) quest. The whole party travels together, so every alive hero enters the vault:
 *  - entering: each other hero also hands its belongings to its own EscapeCrystal (onEnterVault,
 *    called from CityLevel). Gold/energy are shared, so only the activating hero's crystal stores
 *    them; the others are stored without GOLD/ENERGY and never overwrite them on restore.
 *  - leaving: the hero using the crystal picks the reward as in solo. Every other alive hero gets
 *    its own belongings back from its own crystal (vault items are lost, as in solo) plus its own
 *    copy of the picked reward (onLeaveVault). Copies that don't fit are held by the Imp and
 *    dropped next to it, like the solo Imp.Quest.reward.
 *  - store/restore run against the owning hero's quickslot (withQuickslot), so items of
 *    different heroes never get mixed up.
 * Old (pre v4) token quest: the first hero turns in the tokens, gets the reward and completes the
 *  quest; the Imp stays and keeps a copy for each other alive hero, who claims it without tokens.
 */
public class CoopQuestsImp {

	// *** quickslots ***

	//runs r while Dungeon.quickslot is hero's own quickslot. Items read/write their quickslot
	//positions through Dungeon.quickslot when their bundles are stored or restored.
	public static void withQuickslot( Hero hero, Runnable r ){
		QuickSlot own = Coop.active() ? CoopQuickslots.of(hero) : null;
		if (own == null || own == Dungeon.quickslot){
			r.run();
			return;
		}
		QuickSlot prev = Dungeon.quickslot;
		Dungeon.quickslot = own;
		try {
			r.run();
		} finally {
			Dungeon.quickslot = prev;
		}
	}

	// *** vault score ***

	//true if the user, or in coop any alive hero, carries an item of this class
	public static boolean partyHas( Hero user, Class<? extends Item> cls ){
		if (user.belongings.getItem(cls) != null) return true;
		if (!Coop.active()) return false;
		for (Hero h : Coop.aliveHeroes()){
			if (h.belongings.getItem(cls) != null) return true;
		}
		return false;
	}

	//dwarf tokens carried by the user, plus in coop by every other alive hero
	public static int partyTokens( Hero user ){
		int count = 0;
		DwarfToken t = user.belongings.getItem(DwarfToken.class);
		if (t != null) count += t.quantity();
		if (!Coop.active()) return count;
		for (Hero h : Coop.aliveHeroes()){
			if (h == user) continue;
			t = h.belongings.getItem(DwarfToken.class);
			if (t != null) count += t.quantity();
		}
		return count;
	}

	// *** entering / leaving the vault ***

	//CityLevel: after the activating hero's belongings were stored, store everyone else's
	public static void onEnterVault( Hero activator ){
		if (!Coop.active()) return;
		for (Hero h : Coop.aliveHeroes()){
			if (h == activator) continue;
			h.live(); //clears all non-persist buffs, resets hunger/regen
			h.HP = h.HT;

			EscapeCrystal crystal = h.belongings.getItem(EscapeCrystal.class);
			if (crystal == null) {
				crystal = new EscapeCrystal();
			} else {
				crystal.detachAll(h.belongings.backpack);
			}
			if (crystal.storedItems == null){
				crystal.storeHeroBelongings(h, false);
			}
			if (!crystal.collect(h.belongings.backpack)){
				h.belongings.backpack.items.add(crystal);
			}
			h.belongings.armor = new ClothArmor();
			h.belongings.armor.identify();
			h.updateHT( false );
		}
	}

	//the number of reward copies onLeaveVault needs (0 in solo)
	public static int copiesNeeded( Hero user ){
		if (!Coop.active()) return 0;
		int n = 0;
		for (Hero h : Coop.aliveHeroes()){
			if (h != user) n++;
		}
		return n;
	}

	//EscapeCrystal.leaveVault: user already got its belongings and the reward back.
	//rewardCopies holds one fresh copy of the reward per other alive hero (null when there is no reward).
	public static void onLeaveVault( Hero user, ArrayList<Item> rewardCopies ){
		if (!Coop.active()) return;
		int next = 0;
		for (Hero h : Coop.aliveHeroes()){
			if (h == user) continue;
			h.live();
			h.HP = h.HT;

			EscapeCrystal crystal = h.belongings.getItem(EscapeCrystal.class);
			if (crystal != null){
				if (crystal.storedItems != null) {
					crystal.restoreHeroBelongings(h, null);
					h.updateHT(false);
				}
				crystal.detachAll(h.belongings.backpack);
			}

			Item copy = rewardCopies != null && next < rewardCopies.size() ? rewardCopies.get(next++) : null;
			if (copy != null){
				giveReward( h, copy );
			}
		}
	}

	private static void giveReward( Hero h, Item reward ){
		if (reward.collect(h.belongings.backpack)){
			GLog.i( Messages.capitalize(Messages.get(Hero.class, "you_now_have", reward.name())) );
		} else {
			//the Imp holds it and drops it next to itself, like Imp.Quest.reward in solo
			pending.add(reward);
		}
	}

	// *** rewards held by the Imp ***

	private static final ArrayList<Item> pending = new ArrayList<>();

	//Imp.act: drops held coop rewards next to the imp
	public static void dropPending( Imp imp ){
		if (pending.isEmpty()) return;
		for (Item i : pending){
			Dungeon.level.drop( i, imp.pos ).sprite.drop();
		}
		pending.clear();
	}

	// *** old token quest ***

	//a copy of the old quest reward, kept for heroes that haven't claimed yet
	private static Item oldRewardCopy = null;

	//Imp.interact, old quest: handles heroes after the first claim. Returns true if handled.
	public static boolean oldInteract( Imp imp, Hero hero ){
		if (!CoopQuests.anyClaimed(CoopQuests.IMP)) return false;
		if (CoopQuests.hasClaimed(CoopQuests.IMP, hero) || oldRewardCopy == null){
			Game.runOnRenderThread(new Callback() {
				@Override
				public void call() {
					GameScene.show( new WndQuest( imp, Messages.get(Imp.class, "cya", Messages.titleCase(hero.name())) ));
				}
			});
			return true;
		}
		//the tokens were already turned in by a teammate, show a stand-in
		final DwarfToken token = new DwarfToken();
		Game.runOnRenderThread(new Callback() {
			@Override
			public void call() {
				GameScene.show( new WndImpOld( imp, token ) );
			}
		});
		return true;
	}

	//WndImpOld: the reward for this claim. Solo / first claim: the quest's reward.
	public static Item oldReward( Item questReward ){
		if (CoopQuests.anyClaimed(CoopQuests.IMP) && oldRewardCopy != null){
			return oldRewardCopy;
		}
		return questReward;
	}

	//WndImpOld: true for the first claim (always true in solo); only that one completes the quest
	public static boolean isFirstOldClaim(){
		return !CoopQuests.anyClaimed(CoopQuests.IMP);
	}

	//WndImpOld, before the reward is identified / picked up. Returns true if the imp must stay
	//(another alive hero still has to claim). Always false in solo.
	public static boolean oldClaim( Hero hero, Item reward ){
		if (!Coop.active()) return false;
		Item copy = reward == null ? null : reward.duplicate();
		boolean stay = CoopQuests.claimAndStay(CoopQuests.IMP, hero);
		oldRewardCopy = stay ? copy : null;
		return stay;
	}

	// *** bundle (called from Imp.Quest reset/store/restore) ***

	private static final String PENDING     = "coop_pending";
	private static final String OLD_COPY    = "coop_old_copy";

	public static void reset(){
		pending.clear();
		oldRewardCopy = null;
		CoopQuests.reset(CoopQuests.IMP);
	}

	public static void store( Bundle node ){
		CoopQuests.store(node, CoopQuests.IMP);
		if (!pending.isEmpty()) node.put(PENDING, pending);
		if (oldRewardCopy != null) node.put(OLD_COPY, oldRewardCopy);
	}

	public static void restore( Bundle node ){
		CoopQuests.restore(node, CoopQuests.IMP);
		pending.clear();
		if (node.contains(PENDING)){
			for (Bundlable b : node.getCollection(PENDING)){
				pending.add((Item) b);
			}
		}
		oldRewardCopy = node.contains(OLD_COPY) ? (Item) node.get(OLD_COPY) : null;
	}
}
