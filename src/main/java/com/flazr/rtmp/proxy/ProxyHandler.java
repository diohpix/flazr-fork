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

package com.flazr.rtmp.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;

import java.net.InetSocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.flazr.rtmp.RtmpDecoder;
import com.flazr.rtmp.RtmpEncoder;
import com.flazr.rtmp.client.ClientHandler;
import com.flazr.rtmp.client.ClientHandshakeHandler;
import com.sun.istack.internal.FinalArrayList;

public class ProxyHandler extends ChannelInboundHandlerAdapter {

	private static EventLoopGroup workerGroup  = new NioEventLoopGroup(2);
    private static final Logger logger = LoggerFactory.getLogger(ProxyHandler.class);

    private final String remoteHost;
    private final int remotePort;

    private volatile Channel outboundChannel;

    public ProxyHandler( String remoteHost, int remotePort) {
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) {        
        final Channel inboundChannel = ctx.channel();
        RtmpProxy.ALL_CHANNELS.add(inboundChannel);
        ctx.channel().config().setAutoRead(false);        
        Bootstrap cb = new Bootstrap();
        cb.group(workerGroup);
        cb.handler(new ChannelInitializer<Channel>() {
			@Override
			protected void initChannel(Channel ch) throws Exception {
				ChannelPipeline p = ch.pipeline();
		        p.addLast("handshaker", new ProxyHandshakeHandler());
		        p.addLast("handler", new OutboundHandler(ctx.channel()));

			}
		});
        ChannelFuture f = cb.connect(new InetSocketAddress(remoteHost, remotePort));
        outboundChannel = f.channel();
        f.addListener(new ChannelFutureListener() {
            @Override public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    logger.info("connected to remote host: {}, port: {}", remoteHost, remotePort);
                    inboundChannel.config().setAutoRead(true);
                } else {                    
                    inboundChannel.close();
                }
            }
        });
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg ) {
        ByteBuf in = (ByteBuf)  msg;
        // logger.debug(">>> [{}] {}", in.readableBytes(), ByteBufs.hexDump(in));
        outboundChannel.write(in);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        logger.info("closing inbound channel");
        if (outboundChannel != null) {
            if(outboundChannel.isActive()){
            	outboundChannel.close();
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
        logger.info("inbound exception: {}", e.getCause().getMessage());
        if(ctx.channel().isActive()){
        	ctx.close();
        }
    }

    private class OutboundHandler extends ChannelInboundHandlerAdapter {

        private final Channel inboundChannel;

        public OutboundHandler(Channel inboundChannel) {
            logger.info("opening outbound channel");
            this.inboundChannel = inboundChannel;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf in = (ByteBuf) msg;
            // logger.debug("<<< [{}] {}", in.readableBytes(), ByteBufs.hexDump(in));
            inboundChannel.write(in);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            logger.info("closing outbound channel");
            if(ctx.channel().isActive()){
            	ctx.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx,Throwable e) {
            logger.info("outbound exception: {}", e.getCause().getMessage());
            if(ctx.channel().isActive()){
            	ctx.close();
            }
        }
    }

}
