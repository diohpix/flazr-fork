package com.flazr.amf;

import static org.junit.Assert.assertEquals;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.Date;

import org.junit.Test;

public class Amf0ValueTest {

    @Test
    public void testEncodeAndDecodeDate() {

        ByteBuf out = Unpooled.buffer();
        Date date = new Date();
        Amf0Value.encode(out, date);
        Date test = (Date) Amf0Value.decode(out);
        assertEquals(date, test);
        
    }

}
