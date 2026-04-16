package com.phhy.rpc.registry.api;

import com.phhy.rpc.common.model.ServiceInstance;

import java.util.List;

public interface ServiceDiscovery {

    List<ServiceInstance> getHealthyInstances(String serviceName);
}
