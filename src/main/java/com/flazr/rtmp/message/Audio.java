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

package com.flazr.rtmp.message;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import com.flazr.rtmp.RtmpHeader;

public class Audio extends DataMessage {

    @Override
    public boolean isConfig() { // TODO now hard coded for mp4a
        return data.readableBytes() > 3 && data.getInt(0) == 0xaf001310;
    }

    public Audio(final RtmpHeader header, final ByteBuf in) {
        super(header, in);
    }

    public Audio(final byte[] ... bytes) {
        super(bytes);
    }

    public Audio(final int time, final byte[] prefix, final byte[] audioData) {
        header.setTime(time);
        data = Unpooled.copiedBuffer(prefix,audioData);
        header.setSize(data.readableBytes());
    }

    public Audio(final int time, final ByteBuf in) {
        super(time, in);
    }
    
    public static Audio empty() {
        Audio empty = new Audio();
        empty.data = Unpooled.EMPTY_BUFFER;
        return empty;
    }

    @Override
    MessageType getMessageType() {
        return MessageType.AUDIO;
    }

}
