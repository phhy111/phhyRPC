package com.phhy.rpc.common.util;

import com.phhy.rpc.common.exception.RpcException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JwtUtils {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final Pattern SUB_PATTERN = Pattern.compile("\"sub\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern EXP_PATTERN = Pattern.compile("\"exp\"\\s*:\\s*(\\d+)");
    private static volatile String secret = "phhy-rpc-default-jwt-secret";
    private static volatile long expireMillis = 30 * 60 * 1000L;

    private JwtUtils() {
    }

    public static void configure(String newSecret, long newExpireMillis) {
        if (newSecret != null && !newSecret.isBlank()) {
            secret = newSecret;
        }
        if (newExpireMillis > 0) {
            expireMillis = newExpireMillis;
        }
    }

    public static String generateToken(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new RpcException("JWT subject 不能为空");
        }
        long now = Instant.now().toEpochMilli();
        long exp = now + expireMillis;
        String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payloadJson = "{\"sub\":\"" + escape(subject) + "\",\"iat\":" + now + ",\"exp\":" + exp + "}";
        String encodedHeader = URL_ENCODER.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String encodedPayload = URL_ENCODER.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = encodedHeader + "." + encodedPayload;
        String signature = sign(signingInput, secret);
        return signingInput + "." + signature;
    }

    public static boolean validateToken(String token) {
        try {
            validateAndGetSubject(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String validateAndGetSubject(String token) {
        if (token == null || token.isBlank()) {
            throw new RpcException("JWT token 不能为空");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new RpcException("JWT token 格式非法");
        }
        String signingInput = parts[0] + "." + parts[1];
        String expectedSignature = sign(signingInput, secret);
        if (!constantTimeEquals(expectedSignature, parts[2])) {
            throw new RpcException("JWT 签名验证失败");
        }
        String payloadJson = new String(URL_DECODER.decode(parts[1]), StandardCharsets.UTF_8);
        long exp = extractLong(payloadJson, EXP_PATTERN, "exp");
        if (Instant.now().toEpochMilli() > exp) {
            throw new RpcException("JWT token 已过期");
        }
        String subject = extractString(payloadJson, SUB_PATTERN, "sub");
        if (subject.isBlank()) {
            throw new RpcException("JWT token 中 subject 非法");
        }
        return subject;
    }

    private static String sign(String content, String secretValue) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretValue.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return URL_ENCODER.encodeToString(signature);
        } catch (Exception e) {
            throw new RpcException("JWT 签名失败", e);
        }
    }

    private static String extractString(String json, Pattern pattern, String fieldName) {
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new RpcException("JWT 缺少字段: " + fieldName);
        }
        return matcher.group(1);
    }

    private static long extractLong(String json, Pattern pattern, String fieldName) {
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new RpcException("JWT 缺少字段: " + fieldName);
        }
        return Long.parseLong(matcher.group(1));
    }

    private static String escape(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
