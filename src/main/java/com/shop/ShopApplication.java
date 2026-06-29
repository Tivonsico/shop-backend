package com.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 电商后端入口
 *
 * @SpringBootApplication = 这是一个 Spring Boot 应用
 * @EnableCaching       = 开启缓存功能（后面商品列表会用）
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling
public class ShopApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShopApplication.class, args);
    }
}
