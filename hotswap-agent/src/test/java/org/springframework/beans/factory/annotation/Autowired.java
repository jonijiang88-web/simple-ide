package org.springframework.beans.factory.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** 无 Spring 依赖的运行时注入注解测试替身。 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Autowired {
}
