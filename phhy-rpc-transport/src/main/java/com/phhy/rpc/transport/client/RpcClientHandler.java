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
    private final NettyRpcClient nettyRpcClient;

    public RpcClientHandler(UnprocessedRequests unprocessedRequests, NettyRpcClient nettyRpcClient) {
        this.unprocessedRequests = unprocessedRequests;
        this.nettyRpcClient = nettyRpcClient;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcMessage msg) throws Exception {
        if (msg.getMsgType() == MsgType.HEARTBEAT_RESP) {
            ClientHeartbeatManager heartbeatManager = nettyRpcClient.getHeartbeatManager();
            if (heartbeatManager != null) {
                io.netty.channel.Channel parentChannel = ctx.channel().parent();
                if (parentChannel != null) {
                    String serverKey = parentChannel.attr(NettyRpcClient.SERVER_KEY_ATTR).get();
                    if (serverKey != null) {
                        heartbeatManager.onHeartbeatResponse(serverKey);
                    }
                }
            }
            log.debug("已收到来自服务器的心跳响应");
            return;
        }

        if (msg.getMsgType() == MsgType.RESPONSE) {
            RpcResponse response = (RpcResponse) msg.getBody();
            unprocessedRequests.complete(response.getRequestId(), response);
            log.debug("已收到 requestId 的响应: {}", response.getRequestId());
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // HTTP/2 每个请求使用独立 stream，stream 正常结束也会触发 inactive，不能在这里把全局请求全部失败。
        // 真正的 TCP 连接关闭由父连接 closeFuture 统一处理。
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("客户端处理器异常", cause);
        ctx.close();
    }
}
