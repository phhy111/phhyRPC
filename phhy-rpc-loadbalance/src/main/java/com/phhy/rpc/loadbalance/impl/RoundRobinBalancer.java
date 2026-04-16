package com.phhy.rpc.loadbalance.impl;

import com.phhy.rpc.common.model.ServiceInstance;
import com.phhy.rpc.loadbalance.api.LoadBalancer;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class RoundRobinBalancer implements LoadBalancer {

    // 使用AtomicInteger维护全局计数器，CAS原子操作保证线程安全
    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public ServiceInstance select(List<ServiceInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            throw new IllegalArgumentException("Instance list is empty");
        }

        // 通过getAndIncrement()获取当前值并对实例列表取模
        int index = Math.abs(counter.getAndIncrement()) % instances.size();
        ServiceInstance selected = instances.get(index);
        log.debug("RoundRobin selected instance: {}:{} (index={})", selected.getHost(), selected.getPort(), index);
        return selected;
    }
}
