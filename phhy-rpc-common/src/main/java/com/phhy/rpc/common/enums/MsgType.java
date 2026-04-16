package com.phhy.rpc.common.enums;

public enum MsgType {

    REQUEST((byte) 0x01),
    RESPONSE((byte) 0x02),
    HEARTBEAT_REQ((byte) 0x03),
    HEARTBEAT_RESP((byte) 0x04);

    private final byte code;

    MsgType(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static MsgType fromCode(byte code) {
        for (MsgType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown message type code: " + code);
    }
}
