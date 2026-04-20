package com.phhy.rpc.common.auth;

public final class AuthContext {

    private static final ThreadLocal<AuthInfo> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(String subject, String token) {
        HOLDER.set(new AuthInfo(subject, token));
    }

    public static String getSubject() {
        AuthInfo authInfo = HOLDER.get();
        return authInfo == null ? null : authInfo.subject;
    }

    public static String getToken() {
        AuthInfo authInfo = HOLDER.get();
        return authInfo == null ? null : authInfo.token;
    }

    public static void clear() {
        HOLDER.remove();
    }

    private static final class AuthInfo {
        private final String subject;
        private final String token;

        private AuthInfo(String subject, String token) {
            this.subject = subject;
            this.token = token;
        }
    }
}
