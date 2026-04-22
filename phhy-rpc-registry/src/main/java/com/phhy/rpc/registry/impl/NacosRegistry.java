package com.phhy.rpc.registry.impl;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.phhy.rpc.common.enums.HealthStatus;
import com.phhy.rpc.common.model.ServiceInstance;
import com.phhy.rpc.registry.api.ServiceRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class NacosRegistry implements ServiceRegistry {

    private final NamingService namingService;
    private static final long INIT_TIMEOUT_MILLIS = 10_000L;

    public NacosRegistry(String serverAddr) {
        try {
            this.namingService = NamingFactory.createNamingService(serverAddr);
            waitForServerReady();
        } catch (NacosException e) {
            throw new RuntimeException("创建 Nacos NamingService 失败", e);
        }
    }

    private void waitForServerReady() {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < INIT_TIMEOUT_MILLIS) {
            try {
                namingService.getServicesOfServer(0, 1, "DEFAULT_GROUP");
                log.info("Nacos 客户端已就绪");
                return;
            } catch (NacosException e) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.warn("Nacos 客户端等待就绪超时，继续执行...");
    }

    @Override
    public void register(ServiceInstance instance) {
        try {
            Instance nacosInstance = new Instance();
            nacosInstance.setIp(instance.getHost());
            nacosInstance.setPort(instance.getPort());
            nacosInstance.setWeight(instance.getWeight());
            nacosInstance.setHealthy(true);

            Map<String, String> metadata = new HashMap<>();
            if (instance.getMetadata() != null) {
                metadata.putAll(instance.getMetadata());
            }
            metadata.put("healthStatus", HealthStatus.UP.name());
            metadata.putIfAbsent("serializeType", "JSON");
            metadata.putIfAbsent("protocolVersion", "1");
            metadata.putIfAbsent("rpcPort", String.valueOf(instance.getPort()));
            metadata.put("lastHeartbeat", String.valueOf(System.currentTimeMillis()));
            nacosInstance.setMetadata(metadata);

            namingService.registerInstance(instance.getServiceName(), nacosInstance);
            log.info("注册服务: {} at {}:{}", instance.getServiceName(), instance.getHost(), instance.getPort());
        } catch (NacosException e) {
            log.error("注册服务失败： {}", instance.getServiceName(), e);
            throw new RuntimeException("注册服务失败", e);
        }
    }

    @Override
    public void deregister(ServiceInstance instance) {
        try {
            namingService.deregisterInstance(instance.getServiceName(), instance.getHost(), instance.getPort());
            log.info("已注销的服务： {} at {}:{}", instance.getServiceName(), instance.getHost(), instance.getPort());
        } catch (NacosException e) {
            log.error("注销服务失败： {}", instance.getServiceName(), e);
            throw new RuntimeException("注销服务失败：", e);
        }
    }

    @Override
    public void updateHealthStatus(ServiceInstance instance, HealthStatus status) {
        try {
            List<Instance> allInstances = namingService.getAllInstances(instance.getServiceName());
            Instance targetInstance = null;
            for (Instance inst : allInstances) {
                if (inst.getIp().equals(instance.getHost()) && inst.getPort() == instance.getPort()) {
                    targetInstance = inst;
                    break;
                }
            }
            if (targetInstance == null) {
                log.warn("未找到健康更新的实例： {}:{}", instance.getHost(), instance.getPort());
                return;
            }
            Map<String, String> metadata = new HashMap<>(targetInstance.getMetadata());
            metadata.put("healthStatus", status.name());
            metadata.put("lastHeartbeat", String.valueOf(System.currentTimeMillis()));
            targetInstance.setMetadata(metadata);

            namingService.deregisterInstance(instance.getServiceName(), targetInstance);
            targetInstance.setHealthy(status == HealthStatus.UP);
            namingService.registerInstance(instance.getServiceName(), targetInstance);
            log.info("更新的健康状况 {}:{} to {}", instance.getHost(), instance.getPort(), status);
        } catch (NacosException e) {
            log.error("无法更新健康状态{}:{}", instance.getHost(), instance.getPort(), e);
        }
    }

    public NamingService getNamingService() {
        return namingService;
    }
}
