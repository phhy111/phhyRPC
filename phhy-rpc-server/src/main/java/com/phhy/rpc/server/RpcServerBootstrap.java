package com.phhy.rpc.server;

import com.phhy.rpc.common.enums.SerializeType;
import com.phhy.rpc.common.model.ServiceInstance;
import com.phhy.rpc.common.util.JwtUtils;
import com.phhy.rpc.common.util.NetUtils;
import com.phhy.rpc.registry.api.ServiceRegistry;
import com.phhy.rpc.server.health.ServerHealthChecker;
import com.phhy.rpc.transport.server.NettyRpcServer;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
//服务端启动引导类，负责把所有组件组装在一起并管理完整生命周期
public class RpcServerBootstrap {

    private int port = 8080;
    private String nacosAddr = "127.0.0.1:8848";
    private SerializeType serializeType = SerializeType.JSON;
    /** 非空时启用 JWT 校验，需与客户端 {@link JwtUtils#configure} 使用相同密钥与过期策略 */
    private String jwtSecret;
    private long jwtExpireMillis = 30 * 60 * 1000L;
    /** 与客户端 {@code EncryptionFilter} 成对开启：入参解密、返回值加密 */
    private boolean sensitiveDataProcessing;
    private final Map<String, Object> serviceRegistry = new HashMap<>();
    private ServiceRegistry nacosRegistry;
    private NettyRpcServer nettyRpcServer;
    private final List<ServerHealthChecker> healthCheckers = new ArrayList<>();

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

    public RpcServerBootstrap jwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
        return this;
    }

    public RpcServerBootstrap jwtExpireMillis(long jwtExpireMillis) {
        this.jwtExpireMillis = jwtExpireMillis;
        return this;
    }

    public RpcServerBootstrap sensitiveDataProcessing(boolean enable) {
        this.sensitiveDataProcessing = enable;
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

        boolean authRequired = jwtSecret != null && !jwtSecret.isBlank();
        if (authRequired) {
            JwtUtils.configure(jwtSecret, jwtExpireMillis);
        }

        nettyRpcServer = new NettyRpcServer(port, serviceRegistry, serializeType, authRequired, sensitiveDataProcessing);
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

            ServerHealthChecker checker = new ServerHealthChecker(nacosRegistry, instance);
            checker.setMonitoredThreadPool(nettyRpcServer.getBusinessExecutor());
            checker.start();
            healthCheckers.add(checker);
        }

        // 注册JVM ShutdownHook实现优雅停机
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "RPC 服务器关闭"));

        log.info("RPC 服务器启动完成，已发布 {} 服务", serviceRegistry.size());
    }

    public void shutdown() {
        log.info("正在关闭 RPC 服务器...");

        // 按顺序执行：停止健康检查 → 从Nacos注销服务 → 关闭Netty Server
        for (ServerHealthChecker checker : healthCheckers) {
            checker.stop();
        }
        healthCheckers.clear();

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

        log.info("RPC 服务器已正常关闭");
    }
}
