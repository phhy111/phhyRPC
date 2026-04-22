package com.phhy.rpc.client;

import com.phhy.rpc.common.enums.SerializeType;
import com.phhy.rpc.common.model.ServiceInstance;
import com.phhy.rpc.common.util.JwtUtils;
import com.phhy.rpc.loadbalance.api.LoadBalancer;
import com.phhy.rpc.loadbalance.impl.RoundRobinBalancer;
import com.phhy.rpc.proxy.RpcClientProxy;
import com.phhy.rpc.proxy.filter.AuthFilter;
import com.phhy.rpc.proxy.filter.Filter;
import com.phhy.rpc.proxy.filter.FilterChain;
import com.phhy.rpc.proxy.filter.LogFilter;
import com.phhy.rpc.registry.api.ServiceDiscovery;
import com.phhy.rpc.registry.cache.ServiceCacheManager;
import com.phhy.rpc.registry.impl.NacosDiscovery;
import com.phhy.rpc.transport.client.ClientHeartbeatManager;
import com.phhy.rpc.transport.client.NettyRpcClient;
import com.phhy.rpc.transport.pool.ConnectionPoolConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class RpcClientBootstrap {

    private String nacosAddr = "127.0.0.1:8848";
    private SerializeType serializeType = SerializeType.JSON;
    private long timeout = 5000;
    private String jwtSecret;
    private long jwtExpireMillis = 30 * 60 * 1000L;
    private String authToken;
    private LoadBalancer loadBalancer;
    private ServiceDiscovery serviceDiscovery;
    private ServiceCacheManager serviceCacheManager;
    private NettyRpcClient nettyRpcClient;
    private ClientHeartbeatManager heartbeatManager;
    private ConnectionPoolConfig connectionPoolConfig;
    private final FilterChain filterChain = new FilterChain();

    public RpcClientBootstrap nacosAddr(String nacosAddr) {
        this.nacosAddr = nacosAddr;
        return this;
    }

    public RpcClientBootstrap serializeType(SerializeType serializeType) {
        this.serializeType = serializeType;
        return this;
    }

    public RpcClientBootstrap timeout(long timeout) {
        this.timeout = timeout;
        return this;
    }

    public RpcClientBootstrap jwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
        return this;
    }

    public RpcClientBootstrap jwtExpireMillis(long jwtExpireMillis) {
        this.jwtExpireMillis = jwtExpireMillis;
        return this;
    }

    public RpcClientBootstrap withAuthToken(String authToken) {
        this.authToken = authToken;
        return this;
    }

    public RpcClientBootstrap loadBalancer(LoadBalancer loadBalancer) {
        this.loadBalancer = loadBalancer;
        return this;
    }

    public RpcClientBootstrap discovery(ServiceDiscovery discovery) {
        this.serviceDiscovery = discovery;
        return this;
    }

    public RpcClientBootstrap connectionPoolConfig(ConnectionPoolConfig config) {
        this.connectionPoolConfig = config;
        return this;
    }

    public RpcClientBootstrap addFilter(Filter filter) {
        this.filterChain.addFilter(filter);
        return this;
    }

    public void start() {
        if (serviceDiscovery == null) {
            serviceDiscovery = new NacosDiscovery(nacosAddr);
        }

        serviceCacheManager = new ServiceCacheManager(serviceDiscovery);

        if (loadBalancer == null) {
            loadBalancer = new RoundRobinBalancer();
        }

        nettyRpcClient = new NettyRpcClient(serializeType, connectionPoolConfig);

        heartbeatManager = new ClientHeartbeatManager(nettyRpcClient.getConnectionPool(), serializeType);
        heartbeatManager.setNettyRpcClient(nettyRpcClient);
        nettyRpcClient.setHeartbeatManager(heartbeatManager);
        heartbeatManager.start();

        if (jwtSecret != null && !jwtSecret.isBlank()) {
            JwtUtils.configure(jwtSecret, jwtExpireMillis);
        }
        if (authToken != null && !authToken.isBlank()) {
            filterChain.addFirst(new AuthFilter(authToken));
        }

        filterChain.addFilter(new LogFilter());

        log.info("RPC 客户端引导完成");
    }

    public <T> T getService(Class<T> interfaceClass) {
        RpcClientProxy proxy = new RpcClientProxy(
                interfaceClass,
                nettyRpcClient,
                serviceCacheManager,
                loadBalancer,
                filterChain,
                timeout);
        return proxy.getProxy();
    }

    public void warmupService(String serviceName) {
        if (nettyRpcClient == null) {
            throw new IllegalStateException("客户端尚未启动，请先调用 start()");
        }
        List<ServiceInstance> instances = serviceCacheManager.getInstances(serviceName);
        if (instances == null || instances.isEmpty()) {
            log.warn("服务 {} 没有可用实例，跳过预热", serviceName);
            return;
        }
        for (ServiceInstance instance : instances) {
            nettyRpcClient.warmup(instance.getHost(), instance.getPort());
            heartbeatManager.addServer(instance.toKey());
        }
        log.info("服务 {} 预热完成，实例数: {}", serviceName, instances.size());
    }

    public void shutdown() {
        log.info("正在关闭 RPC 客户端...");
        if (heartbeatManager != null) {
            heartbeatManager.shutdown();
        }
        if (nettyRpcClient != null) {
            nettyRpcClient.shutdown();
        }
        if (serviceCacheManager != null) {
            serviceCacheManager.shutdown();
        }
        log.info("RPC 客户端已关闭");
    }
}
