package com.mycompany.jdk16annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;



@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface ServiceProvider {
    Class<?> service();
    int position() default Integer.MAX_VALUE;
    String path() default "";
}
