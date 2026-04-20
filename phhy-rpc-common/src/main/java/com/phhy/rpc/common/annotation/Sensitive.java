package com.phhy.rpc.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Sensitive {

    Algorithm algorithm() default Algorithm.AES;

    String key() default "";

    enum Algorithm {
        AES,
        RSA
    }
}
