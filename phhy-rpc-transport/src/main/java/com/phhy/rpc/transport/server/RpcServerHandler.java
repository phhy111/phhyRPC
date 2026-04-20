package com.phhy.rpc.transport.server;

import com.phhy.rpc.common.constant.RpcConstant;
import com.phhy.rpc.common.enums.MsgType;
import com.phhy.rpc.common.enums.SerializeType;
import com.phhy.rpc.common.exception.RpcAuthException;
import com.phhy.rpc.common.exception.RpcException;
import com.phhy.rpc.common.exception.RpcRemoteException;
import com.phhy.rpc.common.auth.AuthContext;
import com.phhy.rpc.common.model.RpcRequest;
import com.phhy.rpc.common.model.RpcResponse;
import com.phhy.rpc.common.util.JwtUtils;
import com.phhy.rpc.protocol.model.RpcMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Array;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
public class RpcServerHandler extends SimpleChannelInboundHandler<RpcMessage> {

    // 服务实例注册表：接口名 -> 实现对象
    private final Map<String, Object> serviceRegistry;
    // 业务线程池，避免阻塞IO线程
    private final ExecutorService businessExecutor;

    public RpcServerHandler(Map<String, Object> serviceRegistry, ExecutorService businessExecutor) {
        this.serviceRegistry = serviceRegistry;
        this.businessExecutor = businessExecutor;
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
            // 提交到业务线程池执行反射调用等耗时操作
            businessExecutor.submit(() -> handleRequest(ctx, msg));
        }
    }

    private void handleRequest(ChannelHandlerContext ctx, RpcMessage msg) {
        RpcRequest request = (RpcRequest) msg.getBody();
        long requestId = msg.getRequestId();
        SerializeType serializeType = msg.getSerializeType();

        try {
            String authToken = request.getAuthToken();
            if (authToken == null || authToken.isBlank()) {
                throw new RpcAuthException("认证失败：请求未携带 JWT token");
            }
            String subject = JwtUtils.validateAndGetSubject(authToken);
            AuthContext.set(subject, authToken);

            // 从服务注册表中查找实现对象
            Object service = serviceRegistry.get(request.getInterfaceName());
            if (service == null) {
                throw new RpcException("未找到服务： " + request.getInterfaceName());
            }

            // 反射调用目标方法
            java.lang.reflect.Method method = service.getClass().getMethod(
                    request.getMethodName(), request.getParameterTypes());
            Object[] alignedArgs = alignArguments(request.getParameters(), method.getParameterTypes());
            Object result = method.invoke(service, alignedArgs);

            // 构造成功响应
            RpcResponse response = RpcResponse.success(requestId, result);
            sendResponse(ctx, requestId, serializeType, response);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // 捕获InvocationTargetException，提取原始异常
            Throwable targetException = e.getTargetException();
            log.error("服务调用失败： {}.{}", request.getInterfaceName(), request.getMethodName(), targetException);
            RpcResponse response = RpcResponse.fail(requestId, targetException);
            sendResponse(ctx, requestId, serializeType, response);
        } catch (Exception e) {
            log.error("服务调用失败： {}.{}", request.getInterfaceName(), request.getMethodName(), e);
            RpcResponse response = RpcResponse.fail(requestId, e);
            sendResponse(ctx, requestId, serializeType, response);
        } finally {
            AuthContext.clear();
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

    private Object[] alignArguments(Object[] args, Class<?>[] parameterTypes) {
        if (parameterTypes == null || parameterTypes.length == 0) {
            return new Object[0];
        }
        Object[] source = args == null ? new Object[0] : args;
        Object[] aligned = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Object value = i < source.length ? source[i] : null;
            aligned[i] = convertValue(value, parameterTypes[i]);
        }
        return aligned;
    }

    private Object convertValue(Object value, Class<?> targetType) {
        if (value == null || targetType == null) {
            return value;
        }
        if (targetType.isInstance(value)) {
            return value;
        }
        if (targetType.isPrimitive()) {
            return convertPrimitive(value, targetType);
        }
        if (Number.class.isAssignableFrom(targetType) && value instanceof Number number) {
            return convertNumber(number, targetType);
        }
        if ((targetType == Boolean.class && value instanceof Boolean)
                || (targetType == Character.class && value instanceof Character)
                || targetType == String.class) {
            return targetType == String.class ? String.valueOf(value) : value;
        }
        if (targetType.isArray() && value instanceof java.util.Collection<?> collection) {
            Class<?> componentType = targetType.getComponentType();
            Object array = Array.newInstance(componentType, collection.size());
            int idx = 0;
            for (Object item : collection) {
                Array.set(array, idx++, convertValue(item, componentType));
            }
            return array;
        }
        if (value instanceof Map<?, ?> map) {
            return mapToObject(map, targetType);
        }
        return value;
    }

    private Object mapToObject(Map<?, ?> map, Class<?> targetType) {
        try {
            Object obj = targetType.getDeclaredConstructor().newInstance();
            for (java.lang.reflect.Field field : getAllFields(targetType)) {
                field.setAccessible(true);
                Object raw = map.get(field.getName());
                if (raw != null) {
                    field.set(obj, convertValue(raw, field.getType()));
                }
            }
            return obj;
        } catch (Exception e) {
            throw new RpcException("参数类型转换失败: " + targetType.getName(), e);
        }
    }

    private java.util.List<java.lang.reflect.Field> getAllFields(Class<?> type) {
        java.util.List<java.lang.reflect.Field> fields = new java.util.ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                fields.add(field);
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    private Object convertPrimitive(Object value, Class<?> primitiveType) {
        if (primitiveType == boolean.class) {
            return (value instanceof Boolean) ? value : Boolean.parseBoolean(String.valueOf(value));
        }
        if (primitiveType == char.class) {
            String str = String.valueOf(value);
            return str.isEmpty() ? '\0' : str.charAt(0);
        }
        Number number = (value instanceof Number)
                ? (Number) value
                : Double.parseDouble(String.valueOf(value));
        return convertNumber(number, primitiveType);
    }

    private Object convertNumber(Number number, Class<?> targetType) {
        if (targetType == byte.class || targetType == Byte.class) {
            return number.byteValue();
        }
        if (targetType == short.class || targetType == Short.class) {
            return number.shortValue();
        }
        if (targetType == int.class || targetType == Integer.class) {
            return number.intValue();
        }
        if (targetType == long.class || targetType == Long.class) {
            return number.longValue();
        }
        if (targetType == float.class || targetType == Float.class) {
            return number.floatValue();
        }
        if (targetType == double.class || targetType == Double.class) {
            return number.doubleValue();
        }
        return number;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("服务器处理程序异常", cause);
        ctx.close();
    }
}
