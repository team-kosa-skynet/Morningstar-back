package com.gaebang.backend.global.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

@Configuration
public class EmailConfig {

    private static Dotenv dotenv = Dotenv.load();
    private static final String TEST_ID = dotenv.get("TEST_ID");
    private static final String TEST_ID_PASSWORD = dotenv.get("TEST_ID_PASSWORD");

    @Bean
    public JavaMailSender javaMailSender() {
        JavaMailSenderImpl javaMailSender = new JavaMailSenderImpl();

        // Google(Gmail) SMTP 서버 정보 설정
        javaMailSender.setHost("smtp.gmail.com");
        javaMailSender.setPort(587); // TLS 포트

        javaMailSender.setUsername(TEST_ID);
        javaMailSender.setPassword(TEST_ID_PASSWORD);

        javaMailSender.setDefaultEncoding("utf-8");
        javaMailSender.setJavaMailProperties(getMailProperties());

        return javaMailSender;
    }

    // Google에 맞는 SMTP 속성으로 변경
    private Properties getMailProperties() {
        Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", "smtp");
        properties.setProperty("mail.smtp.auth", "true");
        properties.setProperty("mail.smtp.starttls.enable", "true"); // TLS 사용 설정
        properties.setProperty("mail.debug", "true");
        // Gmail은 SSL/TLS 보안 연결을 강제하므로 trust 설정은 필요 시 추가
        // properties.setProperty("mail.smtp.ssl.trust", "smtp.gmail.com");
        // properties.setProperty("mail.smtp.ssl.enable", "true");

        return properties;
    }
}