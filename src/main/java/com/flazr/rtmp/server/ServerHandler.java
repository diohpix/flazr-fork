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

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPromise;
import io.netty.channel.group.ChannelGroup;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.flazr.rtmp.RtmpMessage;
import com.flazr.rtmp.RtmpPublisher;
import com.flazr.rtmp.RtmpReader;
import com.flazr.rtmp.RtmpWriter;
import com.flazr.rtmp.message.Audio;
import com.flazr.rtmp.message.BytesRead;
import com.flazr.rtmp.message.ChunkSize;
import com.flazr.rtmp.message.Command;
import com.flazr.rtmp.message.Control;
import com.flazr.rtmp.message.DataMessage;
import com.flazr.rtmp.message.Metadata;
import com.flazr.rtmp.message.SetPeerBw;
import com.flazr.rtmp.message.Video;
import com.flazr.rtmp.message.WindowAckSize;
import com.flazr.util.ChannelUtils;

public class ServerHandler extends ChannelInboundHandlerAdapter {
    
    private static final Logger logger = LoggerFactory.getLogger(ServerHandler.class);
        
    private int bytesReadWindow = 2500000;
    private long bytesRead;
    private long bytesReadLastSent;

    private long bytesWritten;
    private int bytesWrittenWindow = 2500000;
    private int bytesWrittenLastReceived;   

    private ServerApplication application;
    private String clientId;
    private String playName;
    private int streamId;
    private int bufferDuration;

    private RtmpPublisher publisher;    
    private ServerStream subscriberStream;
    private RtmpWriter recorder;

    private boolean aggregateModeEnabled = true;

    public void setAggregateModeEnabled(boolean aggregateModeEnabled) {
        this.aggregateModeEnabled = aggregateModeEnabled;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) {
        RtmpServer.CHANNELS.add(ctx.channel());
        logger.info("opened channel: {}", ctx);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, Throwable  e) {
        ChannelUtils.exceptionCaught(ctx,e);
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx ) {
        logger.info("channel closed: {}", ctx);
        if(publisher != null) {
            publisher.close();
        }
        if(recorder != null) {
            recorder.close();
        }
        unpublishIfLive();
    }
/*
    @Override
    public void writeComplete(final ChannelHandlerContext ctx) throws Exception {
        bytesWritten += e.getWrittenAmount();        
        super.writeComplete(ctx, e);
    }
*/
    @Override
    public void channelRead(final ChannelHandlerContext ctx,Object msg) {
        if(publisher != null && publisher.handle(ctx,msg)) {
            return;
        }
        final Channel channel = ctx.channel();
        final RtmpMessage message = (RtmpMessage) msg;
        bytesRead += message.getHeader().getSize();
        if((bytesRead - bytesReadLastSent) > bytesReadWindow) {
            logger.info("sending bytes read ack after: {}", bytesRead);
            BytesRead ack = new BytesRead(bytesRead);
            channel.write(ack);
            bytesReadLastSent = bytesRead;
        }
        switch(message.getHeader().getMessageType()) {
            case CHUNK_SIZE: // handled by decoder
                break;
            case CONTROL:
                final Control control = (Control) message;
                switch(control.getType()) {
                    case SET_BUFFER:
                        logger.debug("received set buffer: {}", control);
                        bufferDuration = control.getBufferLength();
                        if(publisher != null) {
                            publisher.setBufferDuration(bufferDuration);
                        }
                        break;
                    default:
                        logger.info("ignored control: {}", control);
                }
                break;
            case COMMAND_AMF0:
            case COMMAND_AMF3:
                final Command command = (Command) message;
                final String name = command.getName();
                if(name.equals("connect")) {
                    connectResponse(ctx, command);
                } else if(name.equals("createStream")) {
                    streamId = 1;
                    channel.write(Command.createStreamSuccess(command.getTransactionId(), streamId));
                } else if(name.equals("play")) {
                    playResponse(ctx, command);
                } else if(name.equals("deleteStream")) {
                    int deleteStreamId = ((Double) command.getArg(0)).intValue();
                    logger.info("deleting stream id: {}", deleteStreamId);
                    // TODO ?
                } else if(name.equals("closeStream")) {
                    final int clientStreamId = command.getHeader().getStreamId();
                    logger.info("closing stream id: {}", clientStreamId); // TODO
                    unpublishIfLive();
                } else if(name.equals("pause")) {                    
                    pauseResponse(ctx, command);
                } else if(name.equals("seek")) {                    
                    seekResponse(ctx, command);
                } else if(name.equals("publish")) {
                    publishResponse(ctx, command);
                } else {
                    logger.warn("ignoring command: {}", command);
                    fireNext(ctx);
                }
                return; // NOT break
            case METADATA_AMF0:
            case METADATA_AMF3:
                final Metadata meta = (Metadata) message;
                if(meta.getName().equals("onMetaData")) {
                    logger.info("adding onMetaData message: {}", meta);
                    meta.setDuration(-1);
                    subscriberStream.addConfigMessage(meta);
                }
                broadcast(message);
                break;            
            case AUDIO:
            case VIDEO:
                if(((DataMessage) message).isConfig()) {
                    logger.info("adding config message: {}", message);
                    subscriberStream.addConfigMessage(message);
                }
            case AGGREGATE:
                broadcast(message);
                break;
            case BYTES_READ:
                final BytesRead bytesReadByClient = (BytesRead) message;                
                bytesWrittenLastReceived = bytesReadByClient.getValue();
                logger.debug("bytes read ack from client: {}, actual: {}", bytesReadByClient, bytesWritten);
                break;
            case WINDOW_ACK_SIZE:
                WindowAckSize was = (WindowAckSize) message;
                if(was.getValue() != bytesReadWindow) {
                    channel.write(SetPeerBw.dynamic(bytesReadWindow));
                }
                break;
            case SET_PEER_BW:
                SetPeerBw spb = (SetPeerBw) message;
                if(spb.getValue() != bytesWrittenWindow) {
                    channel.write(new WindowAckSize(bytesWrittenWindow));
                }
                break;
            default:
            logger.warn("ignoring message: {}", message);
        }
        fireNext(ctx);
    }

    private void fireNext(final ChannelHandlerContext ctx) {
        if(publisher != null && publisher.isStarted() && !publisher.isPaused()) {
            publisher.fireNext(ctx, 0);
        }
    }

    //==========================================================================

    private RtmpMessage[] getStartMessages(final RtmpMessage variation) {
        final List<RtmpMessage> list = new ArrayList<RtmpMessage>();
        list.add(new ChunkSize(4096));
        list.add(Control.streamIsRecorded(streamId));
        list.add(Control.streamBegin(streamId));
        if(variation != null) {
            list.add(variation);
        }
        list.add(Command.playStart(playName, clientId));
        list.add(Metadata.rtmpSampleAccess());
        list.add(Audio.empty());
        list.add(Metadata.dataStart());
        return list.toArray(new RtmpMessage[list.size()]);
    }

    private void broadcast(final RtmpMessage message) {
        subscriberStream.getSubscribers().write(message);
        if(recorder != null) {
            recorder.write(message);
        }
    }

    private void writeToStream(final ChannelHandlerContext ctx, final RtmpMessage message) {
        if(message.getHeader().getChannelId() > 2) {
            message.getHeader().setStreamId(streamId);
        }
        ctx.write(message);
    }

    //==========================================================================

    private void connectResponse(final ChannelHandlerContext ctx, final Command connect) {
        final String appName = (String) connect.getObject().get("app");
        clientId = ctx.hashCode() + "";        
        application = ServerApplication.get(appName); // TODO auth, validation
        logger.info("connect, client id: {}, application: {}", clientId, application);
        ctx.write(new WindowAckSize(bytesWrittenWindow));
        ctx.write(SetPeerBw.dynamic(bytesReadWindow));
        ctx.write(Control.streamBegin(streamId));
        final Command result = Command.connectSuccess(connect.getTransactionId());
        ctx.write(result);
        ctx.write(Command.onBWDone());
        ctx.flush();
    }

    private void playResponse(final ChannelHandlerContext ctx, final Command play) {
        int playStart = -2;
        int playLength = -1;
        if(play.getArgCount() > 1) {
            playStart = ((Double) play.getArg(1)).intValue();
        }
        if(play.getArgCount() > 2) {
            playLength = ((Double) play.getArg(2)).intValue();
        }
        final boolean playReset;
        if(play.getArgCount() > 3) {
            playReset = ((Boolean) play.getArg(3));
        } else {
            playReset = true;
        }
        final Command playResetCommand = playReset ? Command.playReset(playName, clientId) : null;
        final String clientPlayName = (String) play.getArg(0);
        final ServerStream stream = application.getStream(clientPlayName);
        logger.debug("play name {}, start {}, length {}, reset {}",
                new Object[]{clientPlayName, playStart, playLength, playReset});
        if(stream.isLive()) {                  
            for(final RtmpMessage message : getStartMessages(playResetCommand)) {
                writeToStream(ctx, message);
            }
            boolean videoConfigPresent = false;
            for(RtmpMessage message : stream.getConfigMessages()) {
                logger.info("writing start meta / config: {}", message);
                if(message.getHeader().isVideo()) {
                    videoConfigPresent = true;
                }
                writeToStream(ctx, message);
            }
            if(!videoConfigPresent) {
                writeToStream(ctx, Video.empty());
            }
            stream.getSubscribers().add(ctx.channel());
            logger.info("client requested live stream: {}, added to stream: {}", clientPlayName, stream);
            return;
        }
        if(!clientPlayName.equals(playName)) {
            playName = clientPlayName;                        
            final RtmpReader reader = application.getReader(playName);
            if(reader == null) {
                ctx.writeAndFlush(Command.playFailed(playName, clientId));
                return;
            }
            publisher = new RtmpPublisher(reader, streamId, bufferDuration, true, aggregateModeEnabled) {
                @Override protected RtmpMessage[] getStopMessages(long timePosition) {
                    return new RtmpMessage[] {
                        Metadata.onPlayStatus(timePosition / 1000, bytesWritten),
                        Command.playStop(playName, clientId),
                        Control.streamEof(streamId)
                    };
                }
            };
        }
        publisher.start(ctx, playStart, playLength, getStartMessages(playResetCommand));
    }

    private void pauseResponse(final ChannelHandlerContext ctx, final Command command) {
        if(publisher == null) {
            logger.debug("cannot pause when live");
            return;
        }
        final boolean paused = ((Boolean) command.getArg(0));
        final int clientTimePosition = ((Double) command.getArg(1)).intValue();
        logger.debug("pause request: {}, client time position: {}", paused, clientTimePosition);
        if(!paused) {            
            logger.debug("doing unpause, seeking and playing");            
            final Command unpause = Command.unpauseNotify(playName, clientId);
            publisher.start(ctx, clientTimePosition, getStartMessages(unpause));
        } else {            
            publisher.pause();
        }
    }

    private void seekResponse(final ChannelHandlerContext ctx, final Command command) {
        if(publisher == null) {
            logger.debug("cannot seek when live");
            return;
        }
        final int clientTimePosition = ((Double) command.getArg(0)).intValue();
        if (!publisher.isPaused()) {
            final Command seekNotify = Command.seekNotify(streamId, clientTimePosition, playName, clientId);
            publisher.start(ctx, clientTimePosition, getStartMessages(seekNotify));
        } else {
            logger.debug("ignoring seek when paused, client time position: {}", clientTimePosition);
        }
    }

    private void publishResponse(final ChannelHandlerContext ctx, final Command command) {
        if(command.getArgCount() > 1) { // publish
            final String streamName = (String) command.getArg(0);
            final String publishTypeString = (String) command.getArg(1);
            logger.info("publish, stream name: {}, type: {}", streamName, publishTypeString);
            subscriberStream = application.getStream(streamName, publishTypeString); // TODO append, record
            if(subscriberStream.getPublisher() != null) {
                logger.info("disconnecting publisher client, stream already in use");
                ChannelFuture future = ctx.writeAndFlush(Command.publishBadName(streamId));
                future.addListener(ChannelFutureListener.CLOSE);
                return;
            }
            subscriberStream.setPublisher(ctx.channel());            
            ctx.write(Command.publishStart(streamName, clientId, streamId));
            ctx.write(new ChunkSize(4096));
            ctx.writeAndFlush(Control.streamBegin(streamId));
            final ServerStream.PublishType publishType = subscriberStream.getPublishType();
            logger.info("created publish stream: {}", subscriberStream);
            switch(publishType) {
                case LIVE:
                    final ChannelGroup subscribers = subscriberStream.getSubscribers();
                    subscribers.write(Command.publishNotify(streamId));
                    writeToStream(subscribers, Video.empty());
                    writeToStream(subscribers, Metadata.rtmpSampleAccess());
                    writeToStream(subscribers, Audio.empty());
                    writeToStream(subscribers, Metadata.dataStart());
                    break;
                case RECORD:
                    recorder = application.getWriter(streamName);
                    break;
                case APPEND:
                    logger.warn("append not implemented yet, un-publishing...");
                    unpublishIfLive();
                    break;
            }
        } else { // un-publish
            final boolean publish = (Boolean) command.getArg(0);
            if(!publish) {
                unpublishIfLive();
            }
        }
    }

    // TODO cleanup
    private void writeToStream(final ChannelGroup channelGroup, final RtmpMessage message) {
        if(message.getHeader().getChannelId() > 2) {
            message.getHeader().setStreamId(streamId);
        }
        channelGroup.write(message);
    }

    private void unpublishIfLive() {
        if(subscriberStream != null && subscriberStream.getPublisher() != null) {
            final Channel channel = subscriberStream.getPublisher();
            if(channel.isWritable()) {
                channel.write(Command.unpublishSuccess(subscriberStream.getName(), clientId, streamId));
            }
            subscriberStream.getSubscribers().write(Command.unpublishNotify(streamId));
            subscriberStream.setPublisher(null);
            logger.debug("publisher disconnected, stream un-published");
        }
        if(recorder != null) {
            recorder.close();
            recorder = null;
        }
    }

	


}
