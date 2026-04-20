package com.phhy.rpc.common.exception;

public class RpcAuthException extends RpcException {

    public RpcAuthException(String message) {
        super(message);
    }

    public RpcAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
