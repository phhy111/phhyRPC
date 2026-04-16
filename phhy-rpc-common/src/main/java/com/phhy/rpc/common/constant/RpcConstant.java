package com.phhy.rpc.common.constant;

public final class RpcConstant {

    private RpcConstant() {
    }

    public static final int HEADER_LENGTH = 18;

    public static final byte[] MAGIC = new byte[]{0x52, 0x50, 0x43, 0x00};

    public static final byte VERSION = 1;

    public static final int DEFAULT_TIMEOUT = 5000;
}
