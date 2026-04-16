package com.phhy.rpc.proxy.filter;

import com.phhy.rpc.common.model.RpcRequest;
import com.phhy.rpc.common.model.RpcResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LogFilter implements Filter {

    @Override
    public void doFilterBefore(RpcRequest request) {
        log.info("RPC invoke: {}.{}({})", request.getInterfaceName(), request.getMethodName(),
                request.getParameters() != null ? java.util.Arrays.toString(request.getParameters()) : "");
    }

    @Override
    public void doFilterAfter(RpcRequest request, RpcResponse response) {
        if (response.isSuccess()) {
            log.info("RPC result: {}.{} -> {}", request.getInterfaceName(), request.getMethodName(), response.getResult());
        } else {
            log.warn("RPC error: {}.{} -> [{}] {}", request.getInterfaceName(), request.getMethodName(),
                    response.getExceptionClass(), response.getExceptionMessage());
        }
    }
}
