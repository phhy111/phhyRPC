package com.phhy.rpc.proxy.filter;

import com.phhy.rpc.common.model.RpcRequest;
import com.phhy.rpc.common.model.RpcResponse;

public interface Filter {

    // 请求发送前拦截
    void doFilterBefore(RpcRequest request);

    // 响应接收后拦截
    void doFilterAfter(RpcRequest request, RpcResponse response);
}
