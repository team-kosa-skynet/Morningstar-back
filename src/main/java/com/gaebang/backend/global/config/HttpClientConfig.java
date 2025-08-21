package com.gaebang.backend.global.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class HttpClientConfig {

    @Bean
    public RestTemplate restTemplate() {  // Payment 도메인에서 사용
        return new RestTemplate();
    }

    @Bean
    public RestClient restClient() {       // Question 도메인에서 사용
        // Connection Pool 설정
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(100);                    // 전체 최대 연결 수
        connectionManager.setDefaultMaxPerRoute(20);           // 경로당 최대 연결 수

        // Request 설정 (타임아웃) - 이미지 생성 API를 위해 타임아웃 대폭 증가
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(30))  // Connection Pool에서 연결 대기 시간
                .setResponseTimeout(Timeout.ofSeconds(600))          // 응답 대기 시간 (10분으로 증가)
                .build();

        // HttpClient 생성
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .setConnectionManagerShared(true)              // Connection Manager 공유
                .evictExpiredConnections()                      // 만료된 연결 자동 제거
                .evictIdleConnections(Timeout.ofSeconds(30))   // 30초 idle 연결 제거
                .build();

        // HttpComponentsClientHttpRequestFactory 설정 - 이미지 생성 API를 위해 타임아웃 증가
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(Duration.ofSeconds(30));     // 연결 타임아웃
        factory.setReadTimeout(Duration.ofSeconds(600));       // 읽기 타임아웃 (10분으로 증가)

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}