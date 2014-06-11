package com.flazr.io.flv;

import static org.junit.Assert.assertEquals;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import org.junit.Test;

import com.flazr.util.Utils;

public class H263PacketTest {

    @Test
    public void testParseH263Header() {
        ByteBuf in = Unpooled.wrappedBuffer(
                Utils.fromHex("00008400814000f0343f"));
        H263Packet packet = new H263Packet(in, 0);
        assertEquals(640, packet.getWidth());
        assertEquals(480, packet.getHeight());
    }

}
