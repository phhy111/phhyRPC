package com.phhy.rpc.common.util;

import com.phhy.rpc.common.annotation.Sensitive;
import com.phhy.rpc.common.security.KeyManager;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class SensitiveDataProcessor {

    private static final String ENCRYPTION_PREFIX = "ENC(";
    private static final String ENCRYPTION_SUFFIX = ")";

    private SensitiveDataProcessor() {
    }

    public static void encryptSensitiveFields(Object root) {
        walk(root, new IdentityHashMap<>(), true);
    }

    public static void decryptSensitiveFields(Object root) {
        walk(root, new IdentityHashMap<>(), false);
    }

    private static void walk(Object current, IdentityHashMap<Object, Boolean> visited, boolean encrypt) {
        if (current == null || isPrimitiveLike(current.getClass()) || visited.containsKey(current)) {
            return;
        }
        visited.put(current, Boolean.TRUE);
        Class<?> clazz = current.getClass();

        if (clazz.isArray()) {
            int len = Array.getLength(current);
            for (int i = 0; i < len; i++) {
                walk(Array.get(current, i), visited, encrypt);
            }
            return;
        }

        if (current instanceof Collection<?> collection) {
            for (Object item : collection) {
                walk(item, visited, encrypt);
            }
            return;
        }

        if (current instanceof Map<?, ?> map) {
            Set<? extends Map.Entry<?, ?>> entries = map.entrySet();
            for (Map.Entry<?, ?> entry : entries) {
                walk(entry.getValue(), visited, encrypt);
            }
            return;
        }

        for (Field field : getAllFields(clazz)) {
            field.setAccessible(true);
            try {
                Object value = field.get(current);
                if (value == null) {
                    continue;
                }
                Sensitive sensitive = field.getAnnotation(Sensitive.class);
                if (sensitive != null && value instanceof String stringValue) {
                    String transformed = encrypt
                            ? encryptValue(stringValue, sensitive)
                            : decryptValue(stringValue, sensitive);
                    field.set(current, transformed);
                } else {
                    walk(value, visited, encrypt);
                }
            } catch (IllegalAccessException e) {
                log.warn("无法访问字段 {}.{}，跳过敏感数据处理", clazz.getName(), field.getName(), e);
            }
        }
    }

    private static Field[] getAllFields(Class<?> clazz) {
        java.util.List<Field> fields = new java.util.ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                fields.add(field);
            }
            current = current.getSuperclass();
        }
        return fields.toArray(new Field[0]);
    }

    private static String encryptValue(String plain, Sensitive sensitive) {
        if (plain == null || plain.isBlank() || isWrapped(plain)) {
            return plain;
        }
        String key = resolveKey(sensitive);
        String encrypted = sensitive.algorithm() == Sensitive.Algorithm.RSA
                ? EncryptionUtils.encryptRsa(plain, key)
                : EncryptionUtils.encryptAes(plain, key);
        return wrap(encrypted);
    }

    private static String decryptValue(String encrypted, Sensitive sensitive) {
        if (encrypted == null || encrypted.isBlank() || !isWrapped(encrypted)) {
            return encrypted;
        }
        String rawValue = unwrap(encrypted);
        String key = resolveKey(sensitive);
        return sensitive.algorithm() == Sensitive.Algorithm.RSA
                ? EncryptionUtils.decryptRsa(rawValue, key)
                : EncryptionUtils.decryptAes(rawValue, key);
    }

    private static String resolveKey(Sensitive sensitive) {
        if (sensitive.key() != null && !sensitive.key().isBlank()) {
            return sensitive.key();
        }
        return KeyManager.getCurrentAesKey();
    }

    private static boolean isWrapped(String value) {
        return value.startsWith(ENCRYPTION_PREFIX) && value.endsWith(ENCRYPTION_SUFFIX);
    }

    private static String wrap(String value) {
        return ENCRYPTION_PREFIX + value + ENCRYPTION_SUFFIX;
    }

    private static String unwrap(String value) {
        return value.substring(ENCRYPTION_PREFIX.length(), value.length() - ENCRYPTION_SUFFIX.length());
    }

    private static boolean isPrimitiveLike(Class<?> clazz) {
        return clazz.isPrimitive()
                || Number.class.isAssignableFrom(clazz)
                || CharSequence.class.isAssignableFrom(clazz)
                || Boolean.class == clazz
                || Character.class == clazz
                || Enum.class.isAssignableFrom(clazz)
                || clazz.getName().startsWith("java.time.")
                || clazz.getName().startsWith("java.lang.");
    }
}
