package com.phhy.rpc.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceInstance implements Serializable {

    private static final long serialVersionUID = 1L;

    private String serviceName;

    private String host;

    private int port;

    private double weight;

    private boolean healthy;

    private Map<String, String> metadata;

    public String toKey() {
        return host + ":" + port;
    }
}
