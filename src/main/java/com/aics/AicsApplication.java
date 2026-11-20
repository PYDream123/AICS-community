package com.aics;

import com.aics.common.AicsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableConfigurationProperties(AicsProperties.class)
@EnableAsync
public class AicsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AicsApplication.class, args);
    }
}
