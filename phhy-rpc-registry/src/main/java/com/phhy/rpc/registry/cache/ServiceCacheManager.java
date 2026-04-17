package com.phhy.rpc.registry.cache;

import com.phhy.rpc.common.model.ServiceInstance;
import com.phhy.rpc.registry.api.ServiceDiscovery;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
public class ServiceCacheManager {

    // 缓存的服务列表
    private final Map<String, List<ServiceInstance>> serviceCache = new ConcurrentHashMap<>();
    // 缓存条目的过期时间戳
    private final Map<String, Long> cacheExpireTime = new ConcurrentHashMap<>();
    // 缓存过期时间（默认60秒）
    private final long cacheExpireMillis;
    // 定时刷新间隔（默认30秒）
    private final long refreshIntervalMillis;
    private final ServiceDiscovery serviceDiscovery;
    private final ScheduledExecutorService scheduler;

    public ServiceCacheManager(ServiceDiscovery serviceDiscovery) {
        this(serviceDiscovery, 60_000L, 30_000L);
    }

    public ServiceCacheManager(ServiceDiscovery serviceDiscovery, long cacheExpireMillis, long refreshIntervalMillis) {
        this.serviceDiscovery = serviceDiscovery;
        this.cacheExpireMillis = cacheExpireMillis;
        this.refreshIntervalMillis = refreshIntervalMillis;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "服务缓存刷新");
            t.setDaemon(true);
            return t;
        });
        // 启动定时刷新任务
        this.scheduler.scheduleAtFixedRate(this::refreshAll, refreshIntervalMillis, refreshIntervalMillis, TimeUnit.MILLISECONDS);
    }

    public List<ServiceInstance> getInstances(String serviceName) {
        long now = System.currentTimeMillis();
        Long expireTime = cacheExpireTime.get(serviceName);

        // 缓存不存在或已过期，从Nacos重新拉取
        if (expireTime == null || now > expireTime) {
            try {
                List<ServiceInstance> instances = serviceDiscovery.getHealthyInstances(serviceName);
                serviceCache.put(serviceName, instances);
                cacheExpireTime.put(serviceName, now + cacheExpireMillis);
                return instances;
            } catch (Exception e) {
                log.warn("获取实例失败 {}, 使用缓存数据", serviceName, e);
                // Nacos不可用时，降级使用本地缓存
                List<ServiceInstance> cached = serviceCache.get(serviceName);
                if (cached != null) {
                    return cached;
                }
                throw e;
            }
        }

        return serviceCache.get(serviceName);
    }

    public void forceRefresh(String serviceName) {
        try {
            List<ServiceInstance> instances = serviceDiscovery.getHealthyInstances(serviceName);
            serviceCache.put(serviceName, instances);
            cacheExpireTime.put(serviceName, System.currentTimeMillis() + cacheExpireMillis);
        } catch (Exception e) {
            log.warn("强制刷新服务失败： {}", serviceName, e);
        }
    }

    private void refreshAll() {
        for (String serviceName : serviceCache.keySet()) {
            try {
                List<ServiceInstance> instances = serviceDiscovery.getHealthyInstances(serviceName);
                serviceCache.put(serviceName, instances);
                cacheExpireTime.put(serviceName, System.currentTimeMillis() + cacheExpireMillis);
            } catch (Exception e) {
                log.warn("刷新服务失败： {}, 保持缓存数据", serviceName, e);
            }
        }
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
