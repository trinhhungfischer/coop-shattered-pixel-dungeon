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

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.glutils.GLVersion;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.watabou.noosa.Game;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Stand-ins for the GPU and the window, so the real game can run without a display.
 * GL calls do nothing; the graphics object reports a fixed screen and a fixed frame time
 * (so animations and actor waits run much faster than real time).
 */
public class HeadlessStubs {

	public static final int WIDTH = 1280;
	public static final int HEIGHT = 720;
	public static final float FRAME_TIME = 0.05f;

	private static int nextId = 1;

	//real time since the previous frame, updated once per frame by the sim
	static volatile float realDelta = 0f;
	private static long lastFrameNanos = 0;

	static void newFrame(){
		long now = System.nanoTime();
		realDelta = lastFrameNanos == 0 ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
		lastFrameNanos = now;
	}

	public static void install(){
		GL30 gl = (GL30) Proxy.newProxyInstance( GL30.class.getClassLoader(), new Class[]{GL30.class},
				(proxy, method, args) -> gl( method, args ) );
		Gdx.gl = Gdx.gl20 = Gdx.gl30 = gl;

		final Graphics original = Gdx.graphics;
		final GLVersion glVersion = new GLVersion( Application.ApplicationType.Desktop, "3.0 headless", "sim", "sim" );
		Gdx.graphics = (Graphics) Proxy.newProxyInstance( Graphics.class.getClassLoader(), new Class[]{Graphics.class},
				(proxy, method, args) -> {
					switch (method.getName()){
						case "getWidth": case "getBackBufferWidth": return WIDTH;
						case "getHeight": case "getBackBufferHeight": return HEIGHT;
						case "getDensity": return 1f;
						case "getPpiX": case "getPpiY": case "getPpcX": case "getPpcY": return 160f;
						case "getDeltaTime": case "getRawDeltaTime":
							//level generation has a real-time watchdog, so loading screens use real time
							if (Game.scene() instanceof InterlevelScene) return realDelta;
							return FRAME_TIME;
						case "getGLVersion": return glVersion;
						case "getGL20": case "getGL30": return gl;
						case "isGL30Available": return true;
						case "supportsExtension": return false;
						case "getBackBufferScale": return 1f;
						case "getDisplayMode":
							return new Graphics.DisplayMode( WIDTH, HEIGHT, 60, 32 ){};
						default:
							try {
								return method.invoke( original, args );
							} catch (InvocationTargetException e){
								return defaultValue( method.getReturnType() );
							}
					}
				});
	}

	private static Object gl( Method method, Object[] args ){
		String name = method.getName();
		switch (name){
			case "glGenTexture": case "glGenBuffer": case "glGenFramebuffer": case "glGenRenderbuffer":
			case "glCreateShader": case "glCreateProgram":
				return nextId++;
			case "glCheckFramebufferStatus":
				return GL30.GL_FRAMEBUFFER_COMPLETE;
			case "glGetShaderiv": case "glGetProgramiv":
				//compile/link status: success
				if (args[2] instanceof IntBuffer) ((IntBuffer) args[2]).put( ((IntBuffer) args[2]).position(), 1 );
				return null;
			case "glGetIntegerv":
				if (args[1] instanceof IntBuffer) ((IntBuffer) args[1]).put( ((IntBuffer) args[1]).position(), 8192 );
				return null;
			case "glGetFloatv":
				if (args[1] instanceof FloatBuffer) ((FloatBuffer) args[1]).put( ((FloatBuffer) args[1]).position(), 1f );
				return null;
			case "glGetString":
				return "headless";
			case "glGetAttribLocation": case "glGetUniformLocation":
				return 0;
		}
		return defaultValue( method.getReturnType() );
	}

	static Object defaultValue( Class<?> type ){
		if (type == void.class) return null;
		if (type == boolean.class) return false;
		if (type == int.class) return 0;
		if (type == long.class) return 0L;
		if (type == float.class) return 0f;
		if (type == double.class) return 0d;
		if (type == short.class) return (short)0;
		if (type == byte.class) return (byte)0;
		if (type == char.class) return (char)0;
		if (type == String.class) return "";
		return null;
	}
}
