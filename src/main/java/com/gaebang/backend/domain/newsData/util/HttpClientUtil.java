package com.gaebang.backend.domain.newsData.util;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class HttpClientUtil {

    // 요청 헤더를 설정
    private void setRequestHeaders(HttpURLConnection connection, Map<String, String> requestHeaders) {
        if (requestHeaders != null) {
            for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
        }
    }

    // GET 요청을 수행
    public String get(String apiUrl, Map<String, String> requestHeaders) {
        HttpURLConnection con = connect(apiUrl);
        try {
            con.setRequestMethod("GET");
            setRequestHeaders(con, requestHeaders);

            int responseCode = con.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                return readBody(con.getInputStream());
            } else {
                String errorResponse = readBody(con.getErrorStream());
                throw new RuntimeException("API 요청 실패. 응답 코드: " + responseCode + ", 응답: " + errorResponse);
            }
        } catch (IOException e) {
            throw new RuntimeException("API 요청과 응답 실패", e);
        } finally {
            con.disconnect();
        }
    }

    // URL 연결을 생성
    private HttpURLConnection connect(String apiUrl) {
        try {
            URL url = new URL(apiUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000); // 연결 타임아웃 5초
            connection.setReadTimeout(10000);   // 읽기 타임아웃 10초
            return connection;
        } catch (MalformedURLException e) {
            throw new RuntimeException("API URL이 잘못되었습니다: " + apiUrl, e);
        } catch (IOException e) {
            throw new RuntimeException("연결이 실패했습니다: " + apiUrl, e);
        }
    }


    // 응답 본문을 읽기
    private String readBody(InputStream inputStream) {
        if (inputStream == null) {
            return "";
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            StringBuilder responseBody = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                responseBody.append(line);
            }
            return responseBody.toString();
        } catch (IOException e) {
            throw new RuntimeException("API 응답을 읽는 데 실패했습니다", e);
        }
    }
}

// 추가하고 싶은 기능은 노션에 있으니 참고바람.