package com.phhy.rpc.serialization.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.phhy.rpc.common.serialization.Serializer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JsonSerializer implements Serializer {

    private final ObjectMapper objectMapper;

    public JsonSerializer() {
        this.objectMapper = new ObjectMapper();
        // JSON中多余字段不导致反序列化失败
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 跳过自引用，处理循环引用场景
        this.objectMapper.configure(SerializationFeature.FAIL_ON_SELF_REFERENCES, false);
    }

    @Override
    public byte[] serialize(Object obj) {
        if (obj == null) {
            return new byte[0];
        }
        try {
            return objectMapper.writeValueAsBytes(obj);
        } catch (Exception e) {
            log.error("JSON 序列化错误", e);
            throw new RuntimeException("JSON 序列化错误", e);
        }
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return objectMapper.readValue(bytes, clazz);
        } catch (Exception e) {
            log.error("JSON 反序列化错误", e);
            throw new RuntimeException("JSON 反序列化错误", e);
        }
    }
}
