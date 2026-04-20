package com.phhy.rpc.common.security;

import com.phhy.rpc.common.util.EncryptionUtils;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class KeyManager {

    private static final AtomicReference<String> CURRENT_AES_KEY =
            new AtomicReference<>(EncryptionUtils.generateAesKey());
    private static final AtomicReference<String> CURRENT_KEY_VERSION =
            new AtomicReference<>("v1");
    private static final Map<String, String> AES_KEY_HISTORY = new ConcurrentHashMap<>();

    static {
        AES_KEY_HISTORY.put(CURRENT_KEY_VERSION.get(), CURRENT_AES_KEY.get());
    }

    private KeyManager() {
    }

    public static String getCurrentAesKey() {
        return CURRENT_AES_KEY.get();
    }

    public static String getCurrentKeyVersion() {
        return CURRENT_KEY_VERSION.get();
    }

    public static String getAesKeyByVersion(String version) {
        return AES_KEY_HISTORY.get(version);
    }

    public static void setCurrentAesKey(String aesKey) {
        CURRENT_AES_KEY.set(aesKey);
        AES_KEY_HISTORY.put(CURRENT_KEY_VERSION.get(), aesKey);
    }

    public static String rotateAesKey() {
        String newVersion = "v-" + UUID.randomUUID();
        String newKey = EncryptionUtils.generateAesKey();
        CURRENT_KEY_VERSION.set(newVersion);
        CURRENT_AES_KEY.set(newKey);
        AES_KEY_HISTORY.put(newVersion, newKey);
        return newVersion;
    }
}
