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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.util.ReferenceCountUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.flazr.rtmp.RtmpPublisher.Event;
import com.flazr.rtmp.message.ChunkSize;
import com.flazr.rtmp.message.Control;

public class RtmpEncoder extends MessageToByteEncoder<RtmpMessage>  {

    private static final Logger logger = LoggerFactory.getLogger(RtmpEncoder.class);

    private int chunkSize = 128;    
    private RtmpHeader[] channelPrevHeaders = new RtmpHeader[RtmpHeader.MAX_CHANNEL_ID];    

    private void clearPrevHeaders() {
        logger.debug("clearing prev stream headers");
        channelPrevHeaders = new RtmpHeader[RtmpHeader.MAX_CHANNEL_ID];
    }
    @Override
    public void encode(ChannelHandlerContext ctx, final RtmpMessage message, ByteBuf pout){
        final ByteBuf in = message.encode();
        final RtmpHeader header = message.getHeader();
        if(header.isChunkSize()) {
            final ChunkSize csMessage = (ChunkSize) message;
            logger.debug("encoder new chunk size: {}", csMessage);
            chunkSize = csMessage.getChunkSize();
        } else if(header.isControl()) {
            final Control control = (Control) message;
            if(control.getType() == Control.Type.STREAM_BEGIN) {
                clearPrevHeaders();
            }
        }
        final int channelId = header.getChannelId();
        header.setSize(in.readableBytes());
        final RtmpHeader prevHeader = channelPrevHeaders[channelId];       
        if(prevHeader != null // first stream message is always large
                && header.getStreamId() > 0 // all control messages always large
                && header.getTime() > 0) { // if time is zero, always large
            if(header.getSize() == prevHeader.getSize()) {
                header.setHeaderType(RtmpHeader.Type.SMALL);
            } else {
                header.setHeaderType(RtmpHeader.Type.MEDIUM);
            }
            final int deltaTime = header.getTime() - prevHeader.getTime();
            if(deltaTime < 0) {
                logger.warn("negative time: {}", header);
                header.setDeltaTime(0);
            } else {
                header.setDeltaTime(deltaTime);
            }
        } else {
			// otherwise force to LARGE
            header.setHeaderType(RtmpHeader.Type.LARGE);
        }
        channelPrevHeaders[channelId] = header;        
        logger.debug(">> {}", message);
        ByteBuf nout = ctx.alloc().buffer(   RtmpHeader.MAX_ENCODED_SIZE + header.getSize() + header.getSize() / chunkSize);
        boolean first = true;
        while(in.isReadable()) {
            final int size = Math.min(chunkSize, in.readableBytes());
            if(first) {                
                header.encode(nout);
                first = false;
            } else {                
                nout.writeBytes(header.getTinyHeader());
            }
            in.readBytes(nout, size);
        }
        ctx.writeAndFlush(nout);
        ReferenceCountUtil.release(nout);
        ReferenceCountUtil.release(message);
    }

}
