package com.phhy.rpc.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RpcRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String interfaceName;

    private String methodName;

    private Class<?>[] parameterTypes;

    private Object[] parameters;

    private long timeout;

    private String authToken;
}
