package com.phhy.rpc.proxy;

import com.phhy.rpc.common.constant.RpcConstant;
import com.phhy.rpc.common.enums.SerializeType;
import com.phhy.rpc.common.exception.RpcException;
import com.phhy.rpc.common.exception.RpcRemoteException;
import com.phhy.rpc.common.exception.RpcTimeoutException;
import com.phhy.rpc.common.model.RpcRequest;
import com.phhy.rpc.common.model.RpcResponse;
import com.phhy.rpc.common.model.ServiceInstance;
import com.phhy.rpc.loadbalance.api.LoadBalancer;
import com.phhy.rpc.proxy.filter.FilterChain;
import com.phhy.rpc.registry.cache.ServiceCacheManager;
import com.phhy.rpc.transport.client.NettyRpcClient;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

@Slf4j
public class RpcClientProxy implements InvocationHandler {

    private final Class<?> interfaceClass;
    private final NettyRpcClient nettyRpcClient;
    private final ServiceCacheManager serviceCacheManager;
    private final LoadBalancer loadBalancer;
    private final FilterChain filterChain;
    private final long timeout;

    public RpcClientProxy(Class<?> interfaceClass,
                          NettyRpcClient nettyRpcClient,
                          ServiceCacheManager serviceCacheManager,
                          LoadBalancer loadBalancer,
                          FilterChain filterChain,
                          long timeout) {
        this.interfaceClass = interfaceClass;
        this.nettyRpcClient = nettyRpcClient;
        this.serviceCacheManager = serviceCacheManager;
        this.loadBalancer = loadBalancer;
        this.filterChain = filterChain;
        this.timeout = timeout;
    }

    @SuppressWarnings("unchecked")
    public <T> T getProxy() {
        return (T) Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class<?>[]{interfaceClass},
                this);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 过滤Object类方法（toString、hashCode、equals），不进行远程调用
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        // 构造RpcRequest
        RpcRequest request = RpcRequest.builder()
                .interfaceName(interfaceClass.getName())
                .methodName(method.getName())
                .parameterTypes(method.getParameterTypes())
                .parameters(args)
                .timeout(timeout > 0 ? timeout : RpcConstant.DEFAULT_TIMEOUT)
                .build();

        // 执行Filter链 - 请求发送前
        if (filterChain != null) {
            filterChain.doFilterBefore(request);
        }

        // 通过ServiceCacheManager从Nacos（本地缓存）获取该服务的健康实例列表
        List<ServiceInstance> instances = serviceCacheManager.getInstances(interfaceClass.getName());
        if (instances == null || instances.isEmpty()) {
            throw new RpcException("No healthy instances available for service: " + interfaceClass.getName());
        }

        // 通过LoadBalancer轮询选择一个服务实例
        ServiceInstance selectedInstance = loadBalancer.select(instances);

        // 通过NettyRpcClient发送请求
        RpcResponse response;
        try {
            response = nettyRpcClient.sendRequest(request, selectedInstance.getHost(), selectedInstance.getPort());
        } catch (RpcTimeoutException e) {
            // 超时后强制刷新缓存
            serviceCacheManager.forceRefresh(interfaceClass.getName());
            throw e;
        } catch (RpcException e) {
            // 连接失败时强制刷新缓存
            serviceCacheManager.forceRefresh(interfaceClass.getName());
            throw e;
        }

        // 执行Filter链 - 响应接收后
        if (filterChain != null) {
            filterChain.doFilterAfter(request, response);
        }

        // 处理响应
        if (response.isSuccess()) {
            return response.getResult();
        } else {
            // 失败则抛出RpcRemoteException，携带远程异常类名和异常消息
            throw new RpcRemoteException(response.getExceptionClass(), response.getExceptionMessage());
        }
    }
}
