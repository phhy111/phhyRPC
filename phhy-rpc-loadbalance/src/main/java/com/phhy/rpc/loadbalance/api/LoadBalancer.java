package com.phhy.rpc.loadbalance.api;

import com.phhy.rpc.common.model.ServiceInstance;

import java.util.List;

public interface LoadBalancer {

    ServiceInstance select(List<ServiceInstance> instances);
}
