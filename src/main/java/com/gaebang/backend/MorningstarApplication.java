package com.gaebang.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
public class MorningstarApplication {

    public static void main(String[] args) {
        SpringApplication.run(MorningstarApplication.class, args);
    }

}
