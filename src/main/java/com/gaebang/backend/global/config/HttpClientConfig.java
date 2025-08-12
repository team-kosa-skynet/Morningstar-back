package com.gaebang.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

@Configuration
public class HttpClientConfig {
    @Bean
    public RestTemplate restTemplate() {  // Payment 도메인에서 사용
        return new RestTemplate();
    }

    @Bean
    public RestClient restClient() {       // Question 도메인에서 사용
        return RestClient.builder().build();
    }
}