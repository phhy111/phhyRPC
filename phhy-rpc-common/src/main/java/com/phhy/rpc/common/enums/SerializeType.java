package com.phhy.rpc.common.enums;

public enum SerializeType {

    JSON((byte) 0x01),
    KRYO((byte) 0x02);

    private final byte code;

    SerializeType(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static SerializeType fromCode(byte code) {
        for (SerializeType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的序列化类型代码： " + code);
    }
}
