package com.example.moneymarket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableScheduling
@EnableRetry
@EnableCaching
public class MoneyMarketApplication {

    public static void main(String[] args) {
        SpringApplication.run(MoneyMarketApplication.class, args);
    }
}
