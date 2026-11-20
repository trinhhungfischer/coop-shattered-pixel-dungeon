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

import com.watabou.utils.Bundle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

/**
 * One TCP connection carrying messages. A message is a Bundle (the game's own save format,
 * JSON + gzip), framed as a 4-byte length followed by the bytes. Every message has a type
 * under the key "t".
 *
 * A reader thread delivers incoming messages to the listener. send() can be called from any
 * thread and never blocks: a writer thread sends queued messages, so one stalled player can't
 * hold up the game or the heartbeats. No game state is touched here: listeners queue messages for
 * the game threads.
 */
public class NetConnection {

	public interface Listener {
		void onMessage( NetConnection connection, Bundle message );
		void onClosed( NetConnection connection, String reason );
	}

	public static final String TYPE = "t";

	private final Socket socket;
	private final DataInputStream in;
	private final DataOutputStream out;
	private final Listener listener;

	private volatile boolean open = true;
	private volatile long lastReceived = System.currentTimeMillis();

	//set by the owner (host: the player's slot)
	public volatile int slot = -1;

	public NetConnection( Socket socket, Listener listener ) throws IOException {
		this.socket = socket;
		this.listener = listener;
		socket.setTcpNoDelay( true );
		in = new DataInputStream( socket.getInputStream() );
		out = new DataOutputStream( new java.io.BufferedOutputStream( socket.getOutputStream(), 64 * 1024 ) );
	}

	//bytes waiting to be sent; a connection that falls this far behind is closed
	private static final long MAX_QUEUED = 32L * 1024 * 1024;
	private static final byte[] CLOSE = new byte[0];
	private final java.util.concurrent.LinkedBlockingQueue<byte[]> outbox = new java.util.concurrent.LinkedBlockingQueue<>();
	private final java.util.concurrent.atomic.AtomicLong queued = new java.util.concurrent.atomic.AtomicLong();

	public void start( String threadName ){
		Thread reader = new Thread( this::readLoop, threadName );
		reader.setDaemon( true );
		reader.start();
		Thread writer = new Thread( this::writeLoop, threadName + "-writer" );
		writer.setDaemon( true );
		writer.start();
	}

	private void writeLoop(){
		try {
			while (true){
				byte[] data = outbox.take();
				if (data == CLOSE) break;
				queued.addAndGet( -data.length );
				out.writeInt( data.length );
				out.write( data );
				if (outbox.isEmpty()) out.flush();
			}
			out.flush();
		} catch (IOException | InterruptedException e){
			//closed below
		}
		closeSocket();
		if (open) close( "send failed" );
	}

	private void closeSocket(){
		try {
			socket.close();
		} catch (IOException ignored){
		}
	}

	private void readLoop(){
		try {
			while (open){
				int length = in.readInt();
				if (length <= 0 || length > CoopNet.MAX_MESSAGE){
					close( "bad message size " + length );
					return;
				}
				byte[] data = new byte[length];
				in.readFully( data );
				lastReceived = System.currentTimeMillis();
				//closed meanwhile (the socket itself closes a little later): nothing more is delivered
				if (!open) return;
				Bundle message = decode( data );
				if (message == null){
					close( "unreadable message" );
					return;
				}
				listener.onMessage( this, message );
			}
		} catch (IOException e){
			close( open ? "connection lost" : "closed" );
		}
	}

	public boolean send( Bundle message ){
		if (!open) return false;
		byte[] data = encode( message );
		if (queued.addAndGet( data.length ) > MAX_QUEUED){
			close( "too far behind" );
			return false;
		}
		outbox.add( data );
		return true;
	}

	public boolean isOpen(){
		return open;
	}

	public long lastReceived(){
		return lastReceived;
	}

	public String remoteAddress(){
		return socket.getInetAddress().getHostAddress();
	}

	public void close( String reason ){
		synchronized (this){
			if (!open) return;
			open = false;
		}
		//what was queued before (a goodbye) still goes out; a stalled peer gets cut off soon
		outbox.add( CLOSE );
		Thread cut = new Thread( () -> {
			try {
				Thread.sleep( 2000 );
			} catch (InterruptedException ignored){
			}
			closeSocket();
		}, "coop-close" );
		cut.setDaemon( true );
		cut.start();
		listener.onClosed( this, reason );
	}

	public static Bundle message( String type ){
		Bundle b = new Bundle();
		b.put( TYPE, type );
		return b;
	}

	public static String type( Bundle message ){
		return message.getString( TYPE );
	}

	public static byte[] encode( Bundle bundle ){
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		Bundle.write( bundle, bytes, true );
		return bytes.toByteArray();
	}

	public static Bundle decode( byte[] data ){
		try {
			return Bundle.read( new ByteArrayInputStream( data ) );
		} catch (IOException e){
			return null;
		}
	}
}
