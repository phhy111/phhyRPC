package com.phhy.rpc.proxy.filter;

import com.phhy.rpc.common.model.RpcRequest;
import com.phhy.rpc.common.model.RpcResponse;
import com.phhy.rpc.common.util.SensitiveDataProcessor;

public class EncryptionFilter implements Filter {

    @Override
    public void doFilterBefore(RpcRequest request) {
        SensitiveDataProcessor.encryptSensitiveFields(request);
    }

    @Override
    public void doFilterAfter(RpcRequest request, RpcResponse response) {
        SensitiveDataProcessor.decryptSensitiveFields(response);
    }
}
