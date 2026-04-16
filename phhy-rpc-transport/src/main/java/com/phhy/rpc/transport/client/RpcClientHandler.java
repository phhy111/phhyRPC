package com.phhy.rpc.transport.client;

import com.phhy.rpc.common.enums.MsgType;
import com.phhy.rpc.common.model.RpcResponse;
import com.phhy.rpc.protocol.model.RpcMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RpcClientHandler extends SimpleChannelInboundHandler<RpcMessage> {

    private final UnprocessedRequests unprocessedRequests;

    public RpcClientHandler(UnprocessedRequests unprocessedRequests) {
        this.unprocessedRequests = unprocessedRequests;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcMessage msg) throws Exception {
        if (msg.getMsgType() == MsgType.HEARTBEAT_RESP) {
            log.debug("Received heartbeat response from server");
            return;
        }

        if (msg.getMsgType() == MsgType.RESPONSE) {
            RpcResponse response = (RpcResponse) msg.getBody();
            // 根据requestId匹配CompletableFuture，完成Future唤醒调用线程
            unprocessedRequests.complete(response.getRequestId(), response);
            log.debug("Received response for requestId: {}", response.getRequestId());
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 连接关闭时，遍历所有未完成请求，全部completeExceptionally
        unprocessedRequests.completeAllExceptionally(
                new RuntimeException("Connection closed: " + ctx.channel().remoteAddress()));
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Client handler exception", cause);
        ctx.close();
    }
}
