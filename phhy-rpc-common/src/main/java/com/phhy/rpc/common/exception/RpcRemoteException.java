package com.phhy.rpc.common.exception;

public class RpcRemoteException extends RpcException {

    private final String remoteExceptionClass;

    public RpcRemoteException(String remoteExceptionClass, String remoteMessage) {
        super("Remote exception: [" + remoteExceptionClass + "] " + remoteMessage);
        this.remoteExceptionClass = remoteExceptionClass;
    }

    public String getRemoteExceptionClass() {
        return remoteExceptionClass;
    }
}
