package com.flazr.rtmp.message;

import io.netty.buffer.ByteBuf;

public class DummyMessage extends AbstractMessage {

	@Override
	public ByteBuf encode() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void decode(ByteBuf in) {
		// TODO Auto-generated method stub

	}

	@Override
	MessageType getMessageType() {
		// TODO Auto-generated method stub
		return null;
	}

}
