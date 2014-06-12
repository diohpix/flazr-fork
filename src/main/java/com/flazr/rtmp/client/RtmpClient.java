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

package com.flazr.rtmp.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.ServerHandshakeStateEvent;

import java.net.InetSocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.flazr.io.f4v.F4vReader;
import com.flazr.rtmp.RtmpDecoder;
import com.flazr.rtmp.RtmpEncoder;
import com.flazr.rtmp.server.ServerStream;
import com.flazr.util.Utils;

public class RtmpClient {

    private static final Logger logger = LoggerFactory.getLogger(RtmpClient.class);
    private static Bootstrap bootstrap;
	private static EventLoopGroup workerGroup;
	
    public static void main(String[] args) {
    	final ClientOptions options = new ClientOptions();
		
		options.setHost("127.0.0.1");
		options.setAppName("mypull");
        options.setStreamName("test1");
        options.setPort(1935);
        options.setClientVersionToUse(Utils.fromHex("00000000"));
        options.setPublishType(ServerStream.PublishType.LIVE);
        options.setStart(0);
        options.setLength(-1);
        options.setBuffer(100);
        options.setThreads(1);
        options.setLoad(1);
        options.setLoop(1);
		options.setReaderToPublish(new F4vReader("/Users/xiphoid/x.m4a"));
        workerGroup = new NioEventLoopGroup(1);
		bootstrap= new Bootstrap();
		bootstrap.group(workerGroup);
		bootstrap.channel(NioSocketChannel.class);
		bootstrap.option(ChannelOption.TCP_NODELAY,true);
		bootstrap.option(ChannelOption.SO_KEEPALIVE , true);
		bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000);
		bootstrap.handler(new ChannelInitializer<Channel>() {
			@Override
			protected void initChannel(Channel ch) throws Exception {
				ChannelPipeline p = ch.pipeline();
				p.addLast("handshaker", new ClientHandshakeHandler(options));
		        p.addLast("decoder", new RtmpDecoder());
		        p.addLast("encoder", new RtmpEncoder());
		        p.addLast("handler", new ClientHandler(options));
			}
		});
			ChannelFuture connectFuture;
			try {
				connectFuture = bootstrap.connect(new InetSocketAddress(options.getHost(),options.getPort())).sync();
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
    }
}
