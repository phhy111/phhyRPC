package com.phhy.rpc.transport.codec;

import com.phhy.rpc.common.enums.MsgType;
import com.phhy.rpc.protocol.codec.RpcMessageEncoder;
import com.phhy.rpc.protocol.model.RpcMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class RpcMessageToHttp2FrameEncoder extends MessageToMessageEncoder<RpcMessage> {

    private static final AsciiString METHOD_POST = AsciiString.cached("POST");
    private static final AsciiString PATH_RPC = AsciiString.cached("/rpc");
    private static final AsciiString SCHEME_HTTPS = AsciiString.cached("https");
    private static final AsciiString STATUS_200 = AsciiString.cached("200");

    private final RpcMessageEncoder messageEncoder = new RpcMessageEncoder();

    @Override
    protected void encode(ChannelHandlerContext ctx, RpcMessage msg, List<Object> out) throws Exception {
        ByteBuf byteBuf = ctx.alloc().buffer();
        messageEncoder.encode(ctx, msg, byteBuf);

        boolean isRequest = msg.getMsgType() == MsgType.REQUEST
                || msg.getMsgType() == MsgType.HEARTBEAT_REQ;

        Http2Headers headers;
        if (isRequest) {
            headers = new DefaultHttp2Headers()
                    .method(METHOD_POST)
                    .path(PATH_RPC)
                    .scheme(SCHEME_HTTPS);
        } else {
            headers = new DefaultHttp2Headers()
                    .status(STATUS_200);
        }
        out.add(new DefaultHttp2HeadersFrame(headers, false));
        out.add(new DefaultHttp2DataFrame(byteBuf, true));

        log.debug("已将 RpcMessage 编码为 HTTP/2 帧：type={}, requestId={}, dataLen={}",
                msg.getMsgType(), msg.getRequestId(), byteBuf.readableBytes());
    }
}
