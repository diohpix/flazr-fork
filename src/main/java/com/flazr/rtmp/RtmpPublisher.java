/*
 * Flazr <http://flazr.com> Copyright (C) 2009  Peter Thomas.
 *
 * This file is part of Flazr.
 *
 * Flazr is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Flazr is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Flazr.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.flazr.rtmp;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.flazr.io.f4v.F4vReader;
import com.flazr.io.flv.FlvReader;

public abstract class RtmpPublisher {
	public static final Timer TIMER;
	static{
		TIMER = new HashedWheelTimer(RtmpConfig.TIMER_TICK_SIZE, TimeUnit.MILLISECONDS);
	}
    private static final Logger logger = LoggerFactory.getLogger(RtmpPublisher.class);

    private final Timer timer;
    private final int timerTickSize;
    private final boolean usingSharedTimer;
    private final boolean aggregateModeEnabled;

    private final RtmpReader reader;
    private int streamId;
    private long startTime;    
    private long seekTime;
    private long timePosition;
    private int currentConversationId;    
    private int playLength = -1;
    private boolean paused;
    private int bufferDuration;
    private ReentrantLock lock;
    
    public static class Event {

        private final int conversationId;

        public Event(final int conversationId) {
            this.conversationId = conversationId;
        }

        public int getConversationId() {
            return conversationId;
        }

    }

    public RtmpPublisher(final RtmpReader reader, final int streamId, final int bufferDuration, 
            boolean useSharedTimer, boolean aggregateModeEnabled) {
        this.aggregateModeEnabled = aggregateModeEnabled;
        this.usingSharedTimer = useSharedTimer;
        if(useSharedTimer) {
            timer = TIMER;
        } else {
            timer = new HashedWheelTimer(RtmpConfig.TIMER_TICK_SIZE, TimeUnit.MILLISECONDS);
        }
        timerTickSize = RtmpConfig.TIMER_TICK_SIZE;
        this.reader = reader;
        this.streamId = streamId;
        this.bufferDuration = bufferDuration;
        this.lock = new ReentrantLock();
        logger.debug("publisher init, streamId: {}", streamId);
    }

    public static RtmpReader getReader(String path) {
        if(path.toLowerCase().startsWith("mp4:")) {
            return new F4vReader(path.substring(4));
        } else if (path.toLowerCase().endsWith(".f4v")) {
            return new F4vReader(path);
        } else {
            return new FlvReader(path);
        }
    }

    public boolean isStarted() {
        return currentConversationId > 0;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setBufferDuration(int bufferDuration) {
        this.bufferDuration = bufferDuration;
    }

    public boolean handle(ChannelHandlerContext ch ,  Object me) {        
    	try{
    		lock.lock();
	        if(me instanceof Event) {
	            final Event pe = (Event) me;
	            if(pe.conversationId != currentConversationId) {
	                logger.debug("stopping obsolete conversation id: {}, current: {}",        pe.getConversationId(), currentConversationId);
	                return true;
	            }
	            write(ch);
	            return true;
	        }
    	}catch(Error e){
    		e.printStackTrace();
    	}finally{
    		lock.unlock();
    	}
        return false;
    }

    public void start(final ChannelHandlerContext ctx, final int seekTime, final int playLength, final RtmpMessage ... messages) {
    	lock.lock();
        this.playLength = playLength;
        start(ctx, seekTime, messages);
        lock.unlock();
    }

    public void start(final ChannelHandlerContext ctx, final int seekTimeRequested, final RtmpMessage ... messages) {
        paused = false;
        currentConversationId++;
        startTime = System.currentTimeMillis();        
        if(seekTimeRequested >= 0) {
            seekTime = reader.seek(seekTimeRequested);
        } else {
            seekTime = 0;
        }
        timePosition = seekTime;
        logger.debug("publish start, seek requested: {} actual seek: {}, play length: {}, conversation: {}",
                new Object[]{seekTimeRequested, seekTime, playLength, currentConversationId});
        for(final RtmpMessage message : messages) {
            writeToStream(ctx.channel(), message);
        }
        for(final RtmpMessage message : reader.getStartMessages()) {
            writeToStream(ctx.channel(), message);
        }
        write(ctx);
    }

    private void writeToStream(final Channel channel, final RtmpMessage message) {
        if(message.getHeader().getChannelId() > 2) {
            message.getHeader().setStreamId(streamId);
            message.getHeader().setTime((int) timePosition);
        }
        channel.writeAndFlush(message);
    }

    private void write(final ChannelHandlerContext ctx) {
    	if(!ctx.channel().isWritable()) {
    		System.out.println("NOT WRITEABLE-------------");
            return;
        }
        final long writeTime = System.currentTimeMillis();
        final RtmpMessage message;
        synchronized(reader) { //=============== SYNCHRONIZE ! =================
            if(reader.hasNext()) {
                message = reader.next();
            } else {
                message = null;
            }
        } //====================================================================
        if (message == null || playLength >= 0 && timePosition > (seekTime + playLength)) {
        	stop(ctx.channel());
            return;
        }
        final long elapsedTime = System.currentTimeMillis() - startTime;
        final long elapsedTimePlusSeek = elapsedTime + seekTime;
        final double clientBuffer = timePosition - elapsedTimePlusSeek;
        if(aggregateModeEnabled && clientBuffer > timerTickSize) { // TODO cleanup
            reader.setAggregateDuration((int) clientBuffer);
        } else {
            reader.setAggregateDuration(0);
        }        
        final RtmpHeader header = message.getHeader();
        final double compensationFactor = clientBuffer / (bufferDuration + timerTickSize);
        final long delay = (long) ((header.getTime() - timePosition) * compensationFactor);
        logger.debug("elapsed: {}, streamed: {}, buffer: {}, factor: {}, delay: {} heder {} message {}" , new Object[]{elapsedTimePlusSeek, timePosition, clientBuffer, compensationFactor, delay,header.getTime(),message});
        timePosition = header.getTime();
        header.setStreamId(streamId);
        ctx.writeAndFlush(message).addListener(new ChannelFutureListener() {
            @Override public void operationComplete(final ChannelFuture cf) {
            	final Event readyForNext = new Event(currentConversationId);
            	if(delay > 0){
	                timer.newTimeout(new TimerTask() {
	                    @Override public void run(Timeout timeout) {
	                        if(logger.isDebugEnabled()) {
	                            logger.debug("running after delay: {}", delay);
	                        }
	                        if(readyForNext.conversationId != currentConversationId) {
	                            logger.debug("pending 'next' event found obsolete, aborting");
	                            return;
	                        }
	                        ctx.pipeline().fireChannelRead(readyForNext);
	                    }
	                }, delay, TimeUnit.MILLISECONDS);
            	}else{
                    ctx.pipeline().fireChannelRead(readyForNext);
            	}
            }
        });
    }
    public void fireNext(final ChannelHandlerContext ctx, final long delay) {
        final Event readyForNext = new Event(currentConversationId);
        if(delay > timerTickSize) {
            timer.newTimeout(new TimerTask() {
                @Override public void run(Timeout timeout) {
                    if(logger.isDebugEnabled()) {
                        logger.debug("running after delay: {}", delay);
                    }
                    if(readyForNext.conversationId != currentConversationId) {
                        logger.debug("pending 'next' event found obsolete, aborting");
                        return;
                    }
                    ctx.pipeline().fireChannelRead(readyForNext);
                }
            }, delay, TimeUnit.MILLISECONDS);
        } else {
        	ctx.pipeline().fireChannelRead(readyForNext);
        }
    }

    public void pause() {
        paused = true;
        currentConversationId++;
    }

    private void stop(final Channel channel) {
        currentConversationId++;
        final long elapsedTime = System.currentTimeMillis() - startTime;
        logger.info("finished, start: {}, elapsed {}, streamed: {}",
                new Object[]{seekTime / 1000, elapsedTime / 1000, (timePosition - seekTime) / 1000});
        for(RtmpMessage message : getStopMessages(timePosition)) {
            writeToStream(channel, message);
        }
    }

    public void close() {
        if(!usingSharedTimer) {
            timer.stop();
        }
        reader.close();        
    }

    protected abstract RtmpMessage[] getStopMessages(long timePosition);

}
