package com.phhy.rpc.benchmark.server;

import com.phhy.rpc.common.enums.HealthStatus;
import com.phhy.rpc.common.enums.SerializeType;
import com.phhy.rpc.registry.api.ServiceRegistry;
import com.phhy.rpc.server.RpcServerBootstrap;

/**
 * 用于基准测试的 RPC 服务端封装
 * 绕过 Nacos 注册中心，支持直连压测
 */
public class BenchmarkRpcServer {

    private final RpcServerBootstrap bootstrap;

    public BenchmarkRpcServer(int port, SerializeType serializeType) {
        // 注入一个空实现的 ServiceRegistry，避免连接 Nacos
        this.bootstrap = new RpcServerBootstrap()
                .port(port)
                .serializeType(serializeType)
                .registry(new ServiceRegistry() {
                    @Override
                    public void register(com.phhy.rpc.common.model.ServiceInstance instance) {
                        // 压测模式下不注册到任何注册中心
                    }

                    @Override
                    public void deregister(com.phhy.rpc.common.model.ServiceInstance instance) {
                        // 压测模式下不注销
                    }

                    @Override
                    public void updateHealthStatus(com.phhy.rpc.common.model.ServiceInstance instance, HealthStatus status) {
                        // 压测模式下不更新健康状态
                    }
                });
    }

    public BenchmarkRpcServer publishService(Class<?> interfaceClass, Object impl) {
        bootstrap.publishService(interfaceClass, impl);
        return this;
    }

    public void start() throws Exception {
        bootstrap.start();
    }

    public void shutdown() {
        bootstrap.shutdown();
    }
}
