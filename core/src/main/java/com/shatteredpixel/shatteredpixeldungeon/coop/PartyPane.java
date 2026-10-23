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
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.HeroSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.noosa.BitmapText;
import com.watabou.noosa.Camera;
import com.watabou.noosa.ColorBlock;
import com.watabou.noosa.Image;
import com.watabou.noosa.ui.Component;

import java.util.ArrayList;

/**
 * Small coop HUD: whose turn it is, plus class icon and HP of every other party hero.
 * Tapping a hero pans the camera to them. Invisible outside of coop.
 */
public class PartyPane extends Component {

	private static final int ENTRY_WIDTH  = 40;
	private static final int ENTRY_HEIGHT = 16;

	private RenderedTextBlock turn;
	private ArrayList<Entry> entries = new ArrayList<>();
	private boolean needsRebuild = true;

	@Override
	protected void createChildren() {
		turn = PixelScene.renderTextBlock(6);
		turn.hardlight(Window.TITLE_COLOR);
		add(turn);
	}

	public void rebuild(){
		needsRebuild = true;
	}

	@Override
	public synchronized void update() {
		if (needsRebuild){
			needsRebuild = false;
			doRebuild();
		}
		super.update();
	}

	private void doRebuild(){
		for (Entry e : entries){
			e.destroy();
			remove(e);
		}
		entries.clear();

		visible = Coop.active() && Dungeon.hero != null;
		if (!visible) return;

		turn.text(Messages.get(this, "turn", Coop.indexOf(Dungeon.hero)+1));

		for (Hero h : Coop.heroes()){
			if (h == Dungeon.hero) continue;
			Entry e = new Entry(h);
			add(e);
			entries.add(e);
		}
		layout();
	}

	@Override
	protected void layout() {
		turn.setPos(x, y);
		PixelScene.align(turn);
		float pos = turn.bottom() + 1;
		for (Entry e : entries){
			e.setRect(x, pos, ENTRY_WIDTH, ENTRY_HEIGHT);
			pos = e.bottom() + 1;
		}
		width = ENTRY_WIDTH;
		height = pos - y;
	}

	private static class Entry extends Button {

		private Hero hero;

		private ColorBlock bg;
		private Image avatar;
		private BitmapText label;
		private ColorBlock hpBg;
		private ColorBlock hp;

		private int lastTier = -1;

		Entry( Hero hero ){
			super();
			this.hero = hero;

			avatar = HeroSprite.avatar(hero);
			lastTier = hero.tier();
			add(avatar);

			label = new BitmapText(PixelScene.pixelFont);
			label.text(Messages.get(PartyPane.class, "player", Coop.indexOf(hero)+1));
			label.measure();
			add(label);
		}

		@Override
		protected void createChildren() {
			super.createChildren();
			bg = new ColorBlock(1, 1, 0x88000000);
			add(bg);
			hpBg = new ColorBlock(1, 1, 0xFF440000);
			add(hpBg);
			hp = new ColorBlock(1, 1, 0xFFCC0000);
			add(hp);
		}

		@Override
		protected void layout() {
			super.layout();
			bg.x = x;
			bg.y = y;
			bg.size(width, height);

			avatar.x = x + 1;
			avatar.y = y + (height - avatar.height())/2f;
			PixelScene.align(avatar);

			label.x = avatar.x + avatar.width() + 2;
			label.y = y + 2;
			PixelScene.align(label);

			hpBg.x = hp.x = label.x;
			hpBg.y = hp.y = y + height - 5;
			hpBg.size(width - (label.x - x) - 2, 3);
			hp.size(hpBg.width(), 3);
		}

		@Override
		public void update() {
			super.update();
			boolean dead = Coop.isDead(hero) || !hero.isAlive();
			float pct = dead || hero.HT <= 0 ? 0 : Math.min(1f, hero.HP / (float)hero.HT);
			hp.scale.x = pct * hpBg.width();
			if (dead){
				avatar.tint(0x000000, 0.6f);
				label.hardlight(0x888888);
			} else {
				if (hero.tier() != lastTier){
					lastTier = hero.tier();
					avatar.copy(HeroSprite.avatar(hero));
				}
				avatar.resetColor();
				label.resetColor();
			}
		}

		@Override
		protected void onClick() {
			//a teammate in another turn group: take control of them (the game is waiting for input)
			if (Dungeon.hero != null && Dungeon.hero.ready) CoopTime.requestFocus( hero );
			if (hero.sprite != null && hero.sprite.parent != null){
				Camera.main.panTo(hero.sprite.center(), 5f);
			}
		}

		@Override
		protected String hoverText() {
			return Messages.titleCase(hero.heroClass.title());
		}
	}
}
