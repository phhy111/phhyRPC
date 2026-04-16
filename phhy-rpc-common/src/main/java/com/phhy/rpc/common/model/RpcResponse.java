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
public class RpcResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private long requestId;

    private Object result;

    private String exceptionClass;

    private String exceptionMessage;

    public static RpcResponse success(long requestId, Object result) {
        return RpcResponse.builder()
                .requestId(requestId)
                .result(result)
                .build();
    }

    public static RpcResponse fail(long requestId, Throwable throwable) {
        return RpcResponse.builder()
                .requestId(requestId)
                .exceptionClass(throwable.getClass().getName())
                .exceptionMessage(throwable.getMessage())
                .build();
    }

    public boolean isSuccess() {
        return exceptionClass == null;
    }
}
