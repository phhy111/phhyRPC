package com.phhy.rpc.serialization.impl;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.ByteArrayBuilder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.phhy.rpc.common.serialization.Serializer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * 基于 Jackson 的 JSON 序列化实现
 * 优化点：
 * 1. ObjectMapper / ObjectWriter / ObjectReader 单例复用
 * 2. 使用 ThreadLocal<ByteArrayBuilder> 避免每次序列化创建新的 byte[]
 * 3. 关闭 JsonGenerator 时自动回收内部缓冲区
 */
@Slf4j
public class JsonSerializer implements Serializer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ObjectWriter OBJECT_WRITER;
    private static final ObjectReader OBJECT_READER;

    static {
        OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        OBJECT_MAPPER.configure(SerializationFeature.FAIL_ON_SELF_REFERENCES, false);
        OBJECT_WRITER = OBJECT_MAPPER.writer();
        OBJECT_READER = OBJECT_MAPPER.reader();
    }

    private static final ThreadLocal<ByteArrayBuilder> BYTE_ARRAY_BUILDER = ThreadLocal.withInitial(() -> new ByteArrayBuilder(null, 512));

    @Override
    public byte[] serialize(Object obj) {
        if (obj == null) {
            return new byte[0];
        }
        ByteArrayBuilder builder = BYTE_ARRAY_BUILDER.get();
        builder.reset();
        try {
            JsonGenerator generator = OBJECT_MAPPER.getFactory().createGenerator(builder);
            OBJECT_WRITER.writeValue(generator, obj);
            generator.flush();
            generator.close();
            return builder.toByteArray();
        } catch (Exception e) {
            log.error("JSON 序列化错误", e);
            throw new RuntimeException("JSON 序列化错误", e);
        } finally {
            builder.reset();
        }
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return OBJECT_READER.forType(clazz).readValue(bytes);
        } catch (Exception e) {
            log.error("JSON 反序列化错误", e);
            throw new RuntimeException("JSON 反序列化错误", e);
        }
    }
}
