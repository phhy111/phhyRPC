package com.phhy.rpc.transport.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Http2StreamFrameToByteBufDecoder extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Http2HeadersFrame) {
            ReferenceCountUtil.release(msg);
        } else if (msg instanceof Http2DataFrame) {
            Http2DataFrame dataFrame = (Http2DataFrame) msg;
            boolean endStream = dataFrame.isEndStream();
            ByteBuf content = dataFrame.content();
            content.retain();
            ReferenceCountUtil.release(dataFrame);
            ctx.fireChannelRead(content);
            if (endStream) {
                ctx.fireChannelReadComplete();
            }
        } else if (msg instanceof Http2ResetFrame) {
            Http2ResetFrame resetFrame = (Http2ResetFrame) msg;
            log.warn("收到 HTTP/2 RST_STREAM 帧，errorCode={}，关闭流通道", resetFrame.errorCode());
            ReferenceCountUtil.release(msg);
            ctx.close();
        } else {
            ctx.fireChannelRead(msg);
        }
    }
}
