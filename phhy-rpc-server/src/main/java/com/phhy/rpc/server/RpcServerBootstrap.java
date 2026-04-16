package com.phhy.rpc.server;

import com.phhy.rpc.common.enums.SerializeType;
import com.phhy.rpc.common.model.ServiceInstance;
import com.phhy.rpc.common.util.NetUtils;
import com.phhy.rpc.registry.api.ServiceRegistry;
import com.phhy.rpc.server.health.ServerHealthChecker;
import com.phhy.rpc.transport.server.NettyRpcServer;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class RpcServerBootstrap {

    private int port = 8080;
    private String nacosAddr = "127.0.0.1:8848";
    private SerializeType serializeType = SerializeType.JSON;
    private final Map<String, Object> serviceRegistry = new HashMap<>();
    private ServiceRegistry nacosRegistry;
    private NettyRpcServer nettyRpcServer;
    private ServerHealthChecker healthChecker;

    public RpcServerBootstrap port(int port) {
        this.port = port;
        return this;
    }

    public RpcServerBootstrap nacosAddr(String nacosAddr) {
        this.nacosAddr = nacosAddr;
        return this;
    }

    public RpcServerBootstrap serializeType(SerializeType serializeType) {
        this.serializeType = serializeType;
        return this;
    }

    public RpcServerBootstrap registry(ServiceRegistry registry) {
        this.nacosRegistry = registry;
        return this;
    }

    // 注册服务实现
    public RpcServerBootstrap publishService(Class<?> interfaceClass, Object impl) {
        serviceRegistry.put(interfaceClass.getName(), impl);
        return this;
    }

    public void start() throws Exception {
        // 创建Nacos注册中心（如果未外部注入）
        if (nacosRegistry == null) {
            nacosRegistry = new com.phhy.rpc.registry.impl.NacosRegistry(nacosAddr);
        }

        // 启动Netty RPC服务端
        nettyRpcServer = new NettyRpcServer(port, serviceRegistry, serializeType);
        nettyRpcServer.start();

        // 注册所有服务到Nacos
        String localHost = NetUtils.getLocalHost();
        for (String interfaceName : serviceRegistry.keySet()) {
            Map<String, String> metadata = new HashMap<>();
            metadata.put("serializeType", serializeType.name());
            metadata.put("protocolVersion", "1");
            metadata.put("rpcPort", String.valueOf(port));

            ServiceInstance instance = ServiceInstance.builder()
                    .serviceName(interfaceName)
                    .host(localHost)
                    .port(port)
                    .weight(1.0)
                    .healthy(true)
                    .metadata(metadata)
                    .build();
            nacosRegistry.register(instance);

            // 启动健康检查
            healthChecker = new ServerHealthChecker(nacosRegistry, instance);
            healthChecker.start();
        }

        // 注册JVM ShutdownHook实现优雅停机
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "rpc-server-shutdown"));

        log.info("RPC Server bootstrap completed, published {} services", serviceRegistry.size());
    }

    public void shutdown() {
        log.info("Shutting down RPC Server...");

        // 按顺序执行：停止健康检查 → 从Nacos注销服务 → 关闭Netty Server
        if (healthChecker != null) {
            healthChecker.stop();
        }

        if (nacosRegistry != null) {
            String localHost = NetUtils.getLocalHost();
            for (String interfaceName : serviceRegistry.keySet()) {
                ServiceInstance instance = ServiceInstance.builder()
                        .serviceName(interfaceName)
                        .host(localHost)
                        .port(port)
                        .build();
                nacosRegistry.deregister(instance);
            }
        }

        if (nettyRpcServer != null) {
            nettyRpcServer.shutdown();
        }

        log.info("RPC Server shut down gracefully");
    }
}
