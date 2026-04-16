package com.phhy.rpc.registry.impl;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.phhy.rpc.common.enums.HealthStatus;
import com.phhy.rpc.common.model.ServiceInstance;
import com.phhy.rpc.registry.api.ServiceDiscovery;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class NacosDiscovery implements ServiceDiscovery {

    private final NamingService namingService;

    public NacosDiscovery(String serverAddr) {
        try {
            this.namingService = NamingFactory.createNamingService(serverAddr);
        } catch (NacosException e) {
            throw new RuntimeException("Failed to create Nacos NamingService", e);
        }
    }

    @Override
    public List<ServiceInstance> getHealthyInstances(String serviceName) {
        try {
            // 从Nacos拉取所有实例（包含不健康的）
            List<Instance> allInstances = namingService.getAllInstances(serviceName);
            List<ServiceInstance> healthyInstances = new ArrayList<>();

            for (Instance instance : allInstances) {
                // 双重过滤：先过滤Nacos自身标记为unhealthy的实例
                if (!instance.isEnabled() || !instance.isHealthy()) {
                    continue;
                }
                // 再过滤自定义元数据healthStatus不为UP的实例
                Map<String, String> metadata = instance.getMetadata();
                if (metadata != null) {
                    String healthStatus = metadata.get("healthStatus");
                    if (healthStatus != null && !HealthStatus.UP.name().equals(healthStatus)) {
                        continue;
                    }
                }

                // 转换为ServiceInstance
                ServiceInstance serviceInstance = ServiceInstance.builder()
                        .serviceName(serviceName)
                        .host(instance.getIp())
                        .port(instance.getPort())
                        .weight(instance.getWeight())
                        .healthy(instance.isHealthy())
                        .metadata(instance.getMetadata() != null ? new HashMap<>(instance.getMetadata()) : new HashMap<>())
                        .build();
                healthyInstances.add(serviceInstance);
            }

            log.debug("Found {} healthy instances for service: {}", healthyInstances.size(), serviceName);
            return healthyInstances;
        } catch (NacosException e) {
            log.error("Failed to get instances for service: {}", serviceName, e);
            throw new RuntimeException("Failed to get instances from Nacos", e);
        }
    }

    public NamingService getNamingService() {
        return namingService;
    }
}
