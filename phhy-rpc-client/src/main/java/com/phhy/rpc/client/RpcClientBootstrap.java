package com.phhy.rpc.client;

import com.phhy.rpc.common.enums.SerializeType;
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
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RpcClientBootstrap {

    private String nacosAddr = "127.0.0.1:8848";
    private SerializeType serializeType = SerializeType.JSON;
    private long timeout = 5000;
    /** 与 {@link JwtUtils#configure} 一致，启用 {@link #withAuthToken} 前应配置 */
    private String jwtSecret;
    private long jwtExpireMillis = 30 * 60 * 1000L;
    private String authToken;
    private LoadBalancer loadBalancer;
    private ServiceDiscovery serviceDiscovery;
    private ServiceCacheManager serviceCacheManager;
    private NettyRpcClient nettyRpcClient;
    private ClientHeartbeatManager heartbeatManager;
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

    /**
     * 为每个请求携带 JWT；请先通过 {@link #jwtSecret(String)} 配置与服务端相同的密钥与过期时间。
     */
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

    public RpcClientBootstrap addFilter(Filter filter) {
        this.filterChain.addFilter(filter);
        return this;
    }

    public void start() {
        // 创建Nacos服务发现（如果未外部注入）
        if (serviceDiscovery == null) {
            serviceDiscovery = new NacosDiscovery(nacosAddr);
        }

        // 创建本地服务缓存管理
        serviceCacheManager = new ServiceCacheManager(serviceDiscovery);

        // 创建负载均衡（默认轮询）
        if (loadBalancer == null) {
            loadBalancer = new RoundRobinBalancer();
        }

        // 创建Netty RPC客户端
        nettyRpcClient = new NettyRpcClient(serializeType);

        // 创建并启动心跳管理
        heartbeatManager = new ClientHeartbeatManager(nettyRpcClient.getChannelManager(), serializeType);
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

    // 为服务接口创建代理对象
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
