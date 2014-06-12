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

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.flazr.rtmp.RtmpHandshake;
import com.flazr.util.Utils;

public class ServerHandshakeHandler extends ByteToMessageDecoder   {

    private static final Logger logger = LoggerFactory.getLogger(ServerHandshakeHandler.class);
    
    private boolean rtmpe;
    private final RtmpHandshake handshake;
    private boolean partOneDone;
    private boolean handshakeDone;

    public ServerHandshakeHandler() {
        handshake = new RtmpHandshake();
    }

	@Override
	protected void decode(io.netty.channel.ChannelHandlerContext ctx,ByteBuf in, List<Object> out) throws Exception {
		if(!partOneDone) {            
            if(in.readableBytes() < RtmpHandshake.HANDSHAKE_SIZE + 1) {
                return;
            }
            handshake.decodeClient0And1(in);
            rtmpe = handshake.isRtmpe();
            ctx.write(handshake.encodeServer0(),ctx.newPromise());
            ctx.write(handshake.encodeServer1(),ctx.newPromise());
            ctx.write(handshake.encodeServer2(),ctx.newPromise());
            partOneDone = true;
        }
        if(!handshakeDone) {
            if(in.readableBytes() < RtmpHandshake.HANDSHAKE_SIZE) {
                return;
            }
            handshake.decodeClient2(in);
            handshakeDone = true;
            logger.info("handshake done, rtmpe: {}", rtmpe);
            if(Arrays.equals(handshake.getPeerVersion(), Utils.fromHex("00000000"))) {
                final ServerHandler serverHandler = ctx.pipeline().get(ServerHandler.class);
                serverHandler.setAggregateModeEnabled(false);
                logger.info("old client version, disabled 'aggregate' mode");
            }
            if(!rtmpe) {
                ctx.pipeline().remove(this);
            }
        }
        out.add(in);
	}

}
