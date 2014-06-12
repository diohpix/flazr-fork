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

package com.flazr.rtmp.server;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ChannelFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.flazr.rtmp.RtmpConfig;
import com.flazr.rtmp.RtmpDecoder;
import com.flazr.rtmp.RtmpEncoder;
import com.flazr.rtmp.client.ClientHandler;
import com.flazr.rtmp.client.ClientHandshakeHandler;
import com.flazr.util.StopMonitor;

public class RtmpServer {

    private static final Logger logger = LoggerFactory.getLogger(RtmpServer.class);

    static {
        RtmpConfig.configureServer();
        CHANNELS = new DefaultChannelGroup("server-channels",null);
        APPLICATIONS = new ConcurrentHashMap<String, ServerApplication>();
        TIMER = new HashedWheelTimer(RtmpConfig.TIMER_TICK_SIZE, TimeUnit.MILLISECONDS);
    }
    
    protected static final ChannelGroup CHANNELS;
    protected static final Map<String, ServerApplication> APPLICATIONS;
    public static final Timer TIMER;

    public static void main(String[] args) throws Exception {

        final Bootstrap bootstrap = new Bootstrap();
        bootstrap.option(ChannelOption.TCP_NODELAY,true);
		bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000);
        bootstrap.handler(new ChannelInitializer<Channel>() {
			@Override
			protected void initChannel(Channel ch) throws Exception {
				ChannelPipeline p = ch.pipeline();
				 p.addLast("handshaker", new ServerHandshakeHandler());
			     p.addLast("decoder", new RtmpDecoder());
			     p.addLast("encoder", new RtmpEncoder());
			     p.addLast("handler", new ServerHandler());
			}
		});
        
        
        
        final InetSocketAddress socketAddress = new InetSocketAddress(RtmpConfig.SERVER_PORT);
        bootstrap.bind(socketAddress);
        logger.info("server started, listening on: {}", socketAddress);

        final Thread monitor = new StopMonitor(RtmpConfig.SERVER_STOP_PORT);
        monitor.start();        
        monitor.join();
        TIMER.stop();
        final ChannelGroupFuture future = CHANNELS.close();
        logger.info("closing channels");
        future.awaitUninterruptibly();
        logger.info("releasing resources");
        logger.info("server stopped");

    }

}
