package com.phhy.rpc.transport.server;

import com.phhy.rpc.common.auth.AuthContext;
import com.phhy.rpc.common.constant.RpcConstant;
import com.phhy.rpc.common.enums.MsgType;
import com.phhy.rpc.common.enums.SerializeType;
import com.phhy.rpc.common.exception.RpcException;
import com.phhy.rpc.common.model.RpcRequest;
import com.phhy.rpc.common.model.RpcResponse;
import com.phhy.rpc.common.util.JwtUtils;
import com.phhy.rpc.common.util.SensitiveDataProcessor;
import com.phhy.rpc.protocol.model.RpcMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * RPC 服务端业务处理器
 * 优化点：
 * 1. 业务逻辑提交到独立线程池，不阻塞 IO 线程
 * 2. 心跳响应在 IO 线程直接处理
 * 3. 使用 RpcMessage 对象池减少 GC
 */
@Slf4j
public class RpcServerHandler extends SimpleChannelInboundHandler<RpcMessage> {

    private final Map<String, Object> serviceRegistry;
    private final ExecutorService businessExecutor;
    private final boolean authRequired;
    private final boolean sensitiveDataProcessing;

    public RpcServerHandler(Map<String, Object> serviceRegistry,
                            ExecutorService businessExecutor,
                            boolean authRequired,
                            boolean sensitiveDataProcessing) {
        this.serviceRegistry = serviceRegistry;
        this.businessExecutor = businessExecutor;
        this.authRequired = authRequired;
        this.sensitiveDataProcessing = sensitiveDataProcessing;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcMessage msg) throws Exception {
        if (msg.getMsgType() == MsgType.HEARTBEAT_REQ) {
            RpcMessage heartbeatResp = RpcMessage.newInstance();
            heartbeatResp.setVersion(RpcConstant.VERSION);
            heartbeatResp.setMsgType(MsgType.HEARTBEAT_RESP);
            heartbeatResp.setSerializeType(msg.getSerializeType());
            heartbeatResp.setRequestId(msg.getRequestId());
            heartbeatResp.setBody(null);
            ctx.writeAndFlush(heartbeatResp).addListener(future -> {
                if (!future.isSuccess()) {
                    heartbeatResp.recycle();
                }
            });
            msg.recycle();
            log.debug("已收到来自客户端的心跳，并已响应");
            return;
        }

        if (msg.getMsgType() == MsgType.REQUEST) {
            try {
                businessExecutor.submit(() -> {
                    try {
                        handleRequest(ctx, msg);
                    } finally {
                        msg.recycle();
                    }
                });
            } catch (RejectedExecutionException e) {
                log.error("业务线程池已满，拒绝请求 requestId={}", msg.getRequestId(), e);
                RpcResponse response = RpcResponse.fail(msg.getRequestId(),
                        new RpcException("服务器繁忙，请稍后重试", e));
                sendResponse(ctx, msg.getRequestId(), msg.getSerializeType(), response);
                msg.recycle();
            }
        }
    }

    private void handleRequest(ChannelHandlerContext ctx, RpcMessage msg) {
        RpcRequest request = (RpcRequest) msg.getBody();
        long requestId = msg.getRequestId();
        SerializeType serializeType = msg.getSerializeType();

        try {
            if (authRequired) {
                String subject = JwtUtils.validateAndGetSubject(request.getAuthToken());
                AuthContext.set(subject, request.getAuthToken());
            }
            try {
                if (sensitiveDataProcessing) {
                    SensitiveDataProcessor.decryptSensitiveFields(request);
                }

                Object service = serviceRegistry.get(request.getInterfaceName());
                if (service == null) {
                    throw new RpcException("未找到服务： " + request.getInterfaceName());
                }

                java.lang.reflect.Method method = service.getClass().getMethod(
                        request.getMethodName(), request.getParameterTypes());
                Object result = method.invoke(service, request.getParameters());

                RpcResponse response = RpcResponse.success(requestId, result);
                if (sensitiveDataProcessing) {
                    SensitiveDataProcessor.encryptSensitiveFields(response);
                }
                sendResponse(ctx, requestId, serializeType, response);
            } finally {
                if (authRequired) {
                    AuthContext.clear();
                }
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable targetException = e.getTargetException();
            log.error("服务调用失败： {}.{}", request.getInterfaceName(), request.getMethodName(), targetException);
            RpcResponse response = RpcResponse.fail(requestId, targetException);
            sendResponse(ctx, requestId, serializeType, response);
        } catch (Exception e) {
            log.error("服务调用失败： {}.{}", request.getInterfaceName(), request.getMethodName(), e);
            RpcResponse response = RpcResponse.fail(requestId, e);
            sendResponse(ctx, requestId, serializeType, response);
        }
    }

    private void sendResponse(ChannelHandlerContext ctx, long requestId, SerializeType serializeType, RpcResponse response) {
        RpcMessage responseMsg = RpcMessage.newInstance();
        responseMsg.setVersion(RpcConstant.VERSION);
        responseMsg.setMsgType(MsgType.RESPONSE);
        responseMsg.setSerializeType(serializeType);
        responseMsg.setRequestId(requestId);
        responseMsg.setBody(response);
        ctx.writeAndFlush(responseMsg).addListener(future -> {
            if (!future.isSuccess()) {
                responseMsg.recycle();
            }
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("服务器处理程序异常", cause);
        ctx.close();
    }
}
