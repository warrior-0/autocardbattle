package com.example.autocardbattle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AutocardbattleApplication {
    public static void main(String[] args) {
        SpringApplication.run(AutocardbattleApplication.class, args);
    }
}
