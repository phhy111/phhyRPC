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

@Slf4j
public class RpcServerHandler extends SimpleChannelInboundHandler<RpcMessage> {

    // 服务实例注册表：接口名 -> 实现对象
    private final Map<String, Object> serviceRegistry;
    // 业务线程池，避免阻塞IO线程
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
        // 心跳请求在IO线程中直接处理，轻量级操作
        if (msg.getMsgType() == MsgType.HEARTBEAT_REQ) {
            RpcMessage heartbeatResp = RpcMessage.builder()
                    .version(RpcConstant.VERSION)
                    .msgType(MsgType.HEARTBEAT_RESP)
                    .serializeType(msg.getSerializeType())
                    .requestId(msg.getRequestId())
                    .body(null)
                    .build();
            ctx.writeAndFlush(heartbeatResp);
            log.debug("已收到来自客户端的心跳，并已响应");
            return;
        }

        if (msg.getMsgType() == MsgType.REQUEST) {
            try {
                businessExecutor.submit(() -> handleRequest(ctx, msg));
            } catch (RejectedExecutionException e) {
                log.error("业务线程池已满，拒绝请求 requestId={}", msg.getRequestId(), e);
                RpcResponse response = RpcResponse.fail(msg.getRequestId(),
                        new RpcException("服务器繁忙，请稍后重试", e));
                sendResponse(ctx, msg.getRequestId(), msg.getSerializeType(), response);
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
        RpcMessage responseMsg = RpcMessage.builder()
                .version(RpcConstant.VERSION)
                .msgType(MsgType.RESPONSE)
                .serializeType(serializeType)
                .requestId(requestId)
                .body(response)
                .build();
        ctx.writeAndFlush(responseMsg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("服务器处理程序异常", cause);
        ctx.close();
    }
}
