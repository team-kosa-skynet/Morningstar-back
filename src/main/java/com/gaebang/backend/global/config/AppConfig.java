package com.gaebang.backend.global.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * @implNote 오직 Bean 등록 하나만 하는 등의 단순 설정을 모아두는 클래스 입니다.
 */

@Configuration
public class AppConfig {

    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        // RestController에서 json 응답 시 null 값의 필드는 아예 보여주지 않도록 설정하는 부분
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        // LocalTime, LocalDateTime 과 같은 시간관련 클래스의 직렬화, 역직렬화 포함한 클래스 설정을 추가
        objectMapper.registerModule(new JavaTimeModule());

        return objectMapper;
    }
}
