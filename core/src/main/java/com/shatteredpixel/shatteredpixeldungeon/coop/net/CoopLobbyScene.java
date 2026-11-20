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

import com.shatteredpixel.shatteredpixeldungeon.Chrome;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.HeroSelectScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.ExitButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.TitleBackground;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput;
import com.watabou.noosa.Camera;
import com.watabou.noosa.ColorBlock;
import com.watabou.noosa.Game;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Online coop lobby (M2): host a game, join one found on the network or by IP address, pick a
 * class, get ready; the host starts the run once everyone is ready.
 *
 * Network callbacks arrive on network threads and are handed to the render thread via a queue.
 */
public class CoopLobbyScene extends PixelScene {

	private static final int WIDTH = 150;

	//null: host on every network interface (LAN play). Tests use the loopback address.
	public static java.net.InetAddress bindAddress = null;
	public static int hostPort = CoopNet.DEFAULT_PORT;

	private enum Mode { MENU, LOBBY }

	private Mode mode = Mode.MENU;
	private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
	private LanDiscovery.Finder finder;

	private Group ui;
	private String status = "";
	private List<CoopNet.Player> lobby = new ArrayList<>();
	private String myClass;
	private boolean myReady = false;
	private float refresh = 0;
	private int shownGames = -1;

	@Override
	public void create() {
		super.create();

		int w = Camera.main.width;
		int h = Camera.main.height;
		add( new TitleBackground( w, h ) );
		add( new ColorBlock( w, h, 0x88000000 ) );

		myClass = (GamesInProgress.selectedClass != null ? GamesInProgress.selectedClass : HeroClass.WARRIOR).name();

		ui = new Group();
		add( ui );

		ExitButton btnExit = new ExitButton(){
			@Override
			protected void onClick() {
				onBackPressed();
			}
		};
		btnExit.setPos( w - btnExit.width(), 0 );
		add( btnExit );

		if (CoopOnline.active()){
			mode = Mode.LOBBY;
		} else {
			finder = new LanDiscovery.Finder();
			finder.start();
		}
		rebuild();
		fadeIn();
	}

	@Override
	public void destroy() {
		if (finder != null) finder.stop();
		super.destroy();
	}

	@Override
	protected void onBackPressed() {
		if (mode == Mode.LOBBY){
			CoopOnline.leave();
			ShatteredPixelDungeon.switchScene( CoopLobbyScene.class );
		} else {
			ShatteredPixelDungeon.switchScene( HeroSelectScene.class );
		}
	}

	@Override
	public void update() {
		super.update();
		CoopRemote.clientFrame(); //the host's first snapshot arrives while the lobby is open
		Runnable r;
		while ((r = pending.poll()) != null) r.run();

		refresh += Game.elapsed;
		if (refresh > 1f){
			refresh = 0;
			if (mode == Mode.MENU && finder != null && finder.games().size() != shownGames) rebuild();
			if (mode == Mode.LOBBY) rebuild(); //pings
		}
	}

	private String txt( String key, Object... args ){
		return Messages.get( CoopLobbyScene.class, key, args );
	}

	// ---------------------------------------------------------------------------------
	// Layout
	// ---------------------------------------------------------------------------------

	private void rebuild(){
		ui.clear();
		float left = (Camera.main.width - WIDTH) / 2f;
		float y = 10;

		RenderedTextBlock title = renderTextBlock( txt( "title" ), 9 );
		title.hardlight( Window.TITLE_COLOR );
		title.setPos( (Camera.main.width - title.width()) / 2f, y );
		ui.add( title );
		y = title.bottom() + 6;

		if (mode == Mode.MENU) y = buildMenu( left, y );
		else y = buildLobby( left, y );

		if (!status.isEmpty()){
			RenderedTextBlock s = renderTextBlock( status, 6 );
			s.maxWidth( WIDTH );
			s.hardlight( 0xFFAA44 );
			s.setPos( left, y + 4 );
			ui.add( s );
		}
	}

	private float buildMenu( float left, float y ){
		RedButton host = new RedButton( txt( "host" ) ){
			@Override
			protected void onClick() {
				hostGame();
			}
		};
		host.setRect( left, y, WIDTH, 18 );
		ui.add( host );
		y = host.bottom() + 3;

		RedButton join = new RedButton( txt( "join_ip" ) ){
			@Override
			protected void onClick() {
				ShatteredPixelDungeon.scene().addToFront( new WndTextInput( txt( "join_title" ), txt( "join_desc" ),
						"", 64, false, txt( "join" ), null ){
					@Override
					public void onSelect( boolean positive, String text ) {
						if (positive && !text.trim().isEmpty()) joinGame( text.trim() );
					}
				});
			}
		};
		join.setRect( left, y, WIDTH, 18 );
		ui.add( join );
		y = join.bottom() + 8;

		RenderedTextBlock lan = renderTextBlock( txt( "lan" ), 7 );
		lan.setPos( left, y );
		ui.add( lan );
		y = lan.bottom() + 3;

		List<LanDiscovery.Game> games = finder != null ? finder.games() : new ArrayList<>();
		shownGames = games.size();
		if (games.isEmpty()){
			RenderedTextBlock none = renderTextBlock( txt( "lan_none" ), 6 );
			none.hardlight( 0x888888 );
			none.setPos( left, y );
			ui.add( none );
			y = none.bottom();
		}
		for (int i = 0; i < Math.min( 4, games.size() ); i++){
			final LanDiscovery.Game g = games.get(i);
			StyledButton b = new StyledButton( Chrome.Type.GREY_BUTTON_TR,
					txt( "lan_game", g.name, g.players, g.maxPlayers, g.address ), 6 ){
				@Override
				protected void onClick() {
					joinGame( g.address + ":" + g.port );
				}
			};
			b.setRect( left, y, WIDTH, 16 );
			ui.add( b );
			y = b.bottom() + 2;
		}
		return y;
	}

	private float buildLobby( float left, float y ){
		boolean isHost = CoopOnline.isHost();
		if (isHost){
			RenderedTextBlock where = renderTextBlock( txt( "hosting", CoopOnline.host.port() ), 6 );
			where.hardlight( 0xAAAAAA );
			where.setPos( left, y );
			ui.add( where );
			y = where.bottom() + 4;
		}

		for (CoopNet.Player p : lobby){
			String cls = Messages.titleCase( HeroClass.valueOf( p.heroClass ).title() );
			String line = txt( "player", p.slot + 1, p.name, cls )
					+ "  " + (p.ready ? txt( "is_ready" ) : txt( "not_ready" ))
					+ (p.slot != 0 && p.ping >= 0 ? "  " + p.ping + "ms" : "");
			RenderedTextBlock row = renderTextBlock( line, 7 );
			if (p.slot == CoopOnline.localSlot) row.hardlight( Window.TITLE_COLOR );
			Image icon = new Image( HeroClass.valueOf( p.heroClass ).spritesheet(), 0, 90, 12, 15 );
			icon.x = left;
			icon.y = y;
			ui.add( icon );
			row.setPos( left + 15, y + (15 - row.height()) / 2f );
			ui.add( row );
			y += 17;
		}
		y += 4;

		RedButton cls = new RedButton( txt( "change_class" ) ){
			@Override
			protected void onClick() {
				pickClass();
			}
		};
		cls.setRect( left, y, WIDTH/2f - 1, 18 );
		ui.add( cls );

		RedButton ready = new RedButton( myReady ? txt( "unready" ) : txt( "ready" ) ){
			@Override
			protected void onClick() {
				setReady( !myReady );
			}
		};
		ready.setRect( left + WIDTH/2f + 1, y, WIDTH/2f - 1, 18 );
		ui.add( ready );
		y = ready.bottom() + 3;

		if (isHost){
			RedButton start = new RedButton( txt( "start" ) ){
				@Override
				protected void onClick() {
					if (CoopOnline.host.startGame()){
						onStarted( CoopOnline.host.players() );
					} else {
						status = txt( "not_all_ready" );
						rebuild();
					}
				}
			};
			start.setRect( left, y, WIDTH, 18 );
			start.enable( CoopOnline.host.allReady() );
			ui.add( start );
			y = start.bottom() + 3;
		}

		RedButton leave = new RedButton( txt( "leave" ) ){
			@Override
			protected void onClick() {
				onBackPressed();
			}
		};
		leave.setRect( left, y, WIDTH, 18 );
		ui.add( leave );
		return leave.bottom();
	}

	// ---------------------------------------------------------------------------------
	// Actions
	// ---------------------------------------------------------------------------------

	private void hostGame(){
		CoopHost host = new CoopHost( Game.versionCode, txt( "host_name" ), myClass, new CoopHost.Events() {
			@Override
			public void onLobbyChanged( List<CoopNet.Player> players ) {
				pending.add( () -> { lobby = players; rebuild(); } );
			}

			@Override
			public void onPlayerLeft( int slot, String name, String reason ) {
				pending.add( () -> { status = txt( "player_left", name ); rebuild(); } );
			}
		});
		try {
			host.start( bindAddress, hostPort, bindAddress == null );
		} catch (IOException e){
			status = txt( "cant_host", hostPort );
			rebuild();
			return;
		}
		CoopOnline.leave();
		CoopOnline.role = CoopOnline.Role.HOST;
		CoopOnline.host = host;
		CoopOnline.localSlot = 0;
		lobby = host.players();
		mode = Mode.LOBBY;
		status = "";
		if (finder != null) finder.stop();
		rebuild();
	}

	private void joinGame( String address ){
		String ip = address;
		int port = CoopNet.DEFAULT_PORT;
		int colon = address.lastIndexOf( ':' );
		if (colon > 0){
			try {
				port = Integer.parseInt( address.substring( colon + 1 ) );
				ip = address.substring( 0, colon );
			} catch (NumberFormatException ignored){
			}
		}
		CoopClient client = new CoopClient( Game.versionCode, new CoopClient.Events() {
			@Override
			public void onWelcome( int slot ) {
				pending.add( () -> {
					CoopOnline.localSlot = slot;
					CoopOnline.client.pick( myClass );
					rebuild();
				} );
			}

			@Override
			public void onRejected( String reason ) {
				pending.add( () -> { leaveLobby( txt( "rejected_" + reason ) ); } );
			}

			@Override
			public void onLobbyChanged( List<CoopNet.Player> players ) {
				pending.add( () -> { lobby = players; rebuild(); } );
			}

			@Override
			public void onStart( List<CoopNet.Player> players ) {
				pending.add( () -> onStarted( players ) );
			}

			@Override
			public void onDisconnected( String reason ) {
				pending.add( () -> {
					if (mode == Mode.LOBBY) leaveLobby( txt( "disconnected" ) );
				} );
			}
		});
		try {
			client.connect( ip, port, txt( "player_name" ), 4000 );
		} catch (IOException e){
			status = txt( "cant_connect", ip, port );
			rebuild();
			return;
		}
		CoopOnline.leave();
		CoopOnline.role = CoopOnline.Role.CLIENT;
		CoopOnline.client = client;
		mode = Mode.LOBBY;
		status = "";
		if (finder != null) finder.stop();
		rebuild();
	}

	private void leaveLobby( String why ){
		CoopOnline.leave();
		mode = Mode.MENU;
		status = why;
		lobby = new ArrayList<>();
		myReady = false;
		finder = new LanDiscovery.Finder();
		finder.start();
		rebuild();
	}

	private void pickClass(){
		final HeroClass[] classes = HeroClass.values();
		String[] names = new String[classes.length];
		for (int i = 0; i < classes.length; i++) names[i] = Messages.titleCase( classes[i].title() );
		ShatteredPixelDungeon.scene().addToFront( new WndOptions( txt( "change_class" ), "", names ){
			@Override
			protected boolean enabled( int index ) {
				return classes[index].isUnlocked();
			}

			@Override
			protected void onSelect( int index ) {
				myClass = classes[index].name();
				myReady = false;
				if (CoopOnline.isHost()) CoopOnline.host.setHostClass( myClass );
				else if (CoopOnline.isClient()) CoopOnline.client.pick( myClass );
			}
		});
	}

	private void setReady( boolean ready ){
		myReady = ready;
		if (CoopOnline.isHost()) CoopOnline.host.setHostReady( ready );
		else if (CoopOnline.isClient()) CoopOnline.client.setReady( ready );
		rebuild();
	}

	//the run starts: the game side takes over (CoopOnlineGame, M3)
	private void onStarted( List<CoopNet.Player> players ){
		CoopOnline.players = players;
		CoopOnlineGame.start();
	}
}
