package com.phhy.rpc.transport.server;

import com.phhy.rpc.common.constant.RpcConstant;
import com.phhy.rpc.common.enums.MsgType;
import com.phhy.rpc.common.enums.SerializeType;
import com.phhy.rpc.protocol.model.RpcMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

@Slf4j
public class ServerHeartbeatHandler extends IdleStateHandler {

    // 读空闲超时时间60秒
    private static final int READER_IDLE_TIME = 60;

    public ServerHeartbeatHandler() {
        super(READER_IDLE_TIME, 0, 0, TimeUnit.SECONDS);
    }

    @Override
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
        if (evt == IdleStateEvent.READER_IDLE_STATE_EVENT) {
            // 超过60秒未收到客户端任何数据，触发READER_IDLE事件
            log.warn("Client idle timeout, closing connection: {}", ctx.channel().remoteAddress());
            // 标记客户端离线，关闭该客户端连接
            ctx.close();
        }
    }
}
