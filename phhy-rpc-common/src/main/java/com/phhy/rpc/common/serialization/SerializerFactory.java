package com.phhy.rpc.common.serialization;

import com.phhy.rpc.common.enums.SerializeType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SerializerFactory {

    private static final Map<SerializeType, Serializer> CACHE = new ConcurrentHashMap<>();

    private SerializerFactory() {
    }

    public static Serializer getSerializer(SerializeType type) {
        return CACHE.computeIfAbsent(type, t -> {
            // 通过反射加载具体实现，避免common模块依赖serialization模块
            try {
                switch (t) {
                    case JSON:
                        return (Serializer) Class.forName("com.phhy.rpc.serialization.impl.JsonSerializer")
                                .getDeclaredConstructor().newInstance();
                    case KRYO:
                        return (Serializer) Class.forName("com.phhy.rpc.serialization.impl.KryoSerializer")
                                .getDeclaredConstructor().newInstance();
                    default:
                        throw new IllegalArgumentException("不支持的序列化类型：" + t);
                }
            } catch (Exception e) {
                throw new RuntimeException("无法为类型创建序列化器：" + t, e);
            }
        });
    }
}
