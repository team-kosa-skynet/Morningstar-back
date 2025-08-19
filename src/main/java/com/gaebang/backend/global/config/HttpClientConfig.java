package com.gaebang.backend.global.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class HttpClientConfig {
    
    @Bean
    public ClientHttpRequestFactory clientHttpRequestFactory() {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(30));  // 연결 타임아웃 30초
        factory.setConnectionRequestTimeout(Duration.ofSeconds(30));  // 연결 풀 타임아웃 30초
        return factory;
    }
    
    @Bean
    public RestTemplate restTemplate() {  // Payment 도메인에서 사용
        return new RestTemplate(clientHttpRequestFactory());
    }

    @Bean
    public RestClient restClient() {       // Question 도메인에서 사용
        return RestClient.builder()
                .requestFactory(clientHttpRequestFactory())
                .build();
    }
}