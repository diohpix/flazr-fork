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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.ReferenceCountUtil;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.flazr.rtmp.RtmpHandshake;

public class ClientHandshakeHandler extends ByteToMessageDecoder   {

    private static final Logger logger = LoggerFactory.getLogger(ClientHandshakeHandler.class);

    private boolean rtmpe;
    private final RtmpHandshake handshake;
    private boolean handshakeDone;

    public ClientHandshakeHandler(ClientOptions options) {
        handshake = new RtmpHandshake(options);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception{
        if(in.readableBytes() < 1 + RtmpHandshake.HANDSHAKE_SIZE * 2) {
            return ;
        }
        handshake.decodeServerAll(in);
        ctx.writeAndFlush(handshake.encodeClient2());
        handshakeDone = true;
        rtmpe = handshake.isRtmpe(); // rare chance server refused rtmpe
        if(handshake.getSwfvBytes() != null) {
            ClientHandler clientHandler = ctx.pipeline().get(ClientHandler.class);
            clientHandler.setSwfvBytes(handshake.getSwfvBytes());
        }
        if(!rtmpe) {
            ctx.pipeline().remove(this); //handshake 더이상  발생안함 
        }
        ctx.fireChannelActive(); // -> RtmpDecodr로 전파 
    }
	@Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception{
		// TODO Auto-generated method stub
			logger.info("connected, starting handshake");                
	        ctx.writeAndFlush(handshake.encodeClient0());
			ctx.writeAndFlush(handshake.encodeClient1());
	}

}
