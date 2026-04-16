package com.phhy.rpc.serialization.impl;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.Pool;
import com.phhy.rpc.common.serialization.Serializer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class KryoSerializer implements Serializer {

    // 使用Pool池化管理Kryo实例，保证线程安全（Kryo 5.x使用Pool替代KryoPool）
    private final Pool<Kryo> kryoPool;
    // Output/Output也需要池化，因为它们也不是线程安全的
    private final Pool<Output> outputPool;
    private final Pool<Input> inputPool;

    public KryoSerializer() {
        kryoPool = new Pool<Kryo>(true, false, 16) {
            @Override
            protected Kryo create() {
                Kryo kryo = new Kryo();
                // 开启引用跟踪，自动处理循环引用
                kryo.setReferences(true);
                // 兼容未注册的类
                kryo.setRegistrationRequired(false);
                return kryo;
            }
        };
        outputPool = new Pool<Output>(true, false, 16) {
            @Override
            protected Output create() {
                return new Output(1024, -1);
            }
        };
        inputPool = new Pool<Input>(true, false, 16) {
            @Override
            protected Input create() {
                return new Input(1024);
            }
        };
    }

    @Override
    public byte[] serialize(Object obj) {
        if (obj == null) {
            return new byte[0];
        }
        Kryo kryo = kryoPool.obtain();
        Output output = outputPool.obtain();
        try {
            kryo.writeClassAndObject(output, obj);
            return output.toBytes();
        } catch (Exception e) {
            log.error("Kryo serialize error", e);
            throw new RuntimeException("Kryo serialize error", e);
        } finally {
            output.reset();
            kryoPool.free(kryo);
            outputPool.free(output);
        }
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        Kryo kryo = kryoPool.obtain();
        Input input = inputPool.obtain();
        try {
            input.setBuffer(bytes);
            Object obj = kryo.readClassAndObject(input);
            return clazz.cast(obj);
        } catch (Exception e) {
            log.error("Kryo deserialize error", e);
            throw new RuntimeException("Kryo deserialize error", e);
        } finally {
            input.setBuffer(null);
            kryoPool.free(kryo);
            inputPool.free(input);
        }
    }
}
