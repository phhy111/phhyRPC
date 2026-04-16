package com.phhy.rpc.registry.api;

import com.phhy.rpc.common.enums.HealthStatus;
import com.phhy.rpc.common.model.ServiceInstance;

public interface ServiceRegistry {

    void register(ServiceInstance instance);

    void deregister(ServiceInstance instance);

    void updateHealthStatus(ServiceInstance instance, HealthStatus status);
}
