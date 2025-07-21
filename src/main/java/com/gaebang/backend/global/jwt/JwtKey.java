package com.gaebang.backend.global.jwt;

import io.github.cdimascio.dotenv.Dotenv;
import io.jsonwebtoken.security.Keys;
import org.springframework.data.util.Pair;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Map;
import java.util.Random;

/**
 * @author liyusang1
 * @implNote JWT Key를 제공하고 조회하는 코드
 */
public class JwtKey {

    private static Dotenv dotenv = Dotenv.load();
    private static final String JWT_SECRET_KEY1 = dotenv.get("JWT_SECRET_KEY1");
    private static final String JWT_SECRET_KEY2 = dotenv.get("JWT_SECRET_KEY2");
    private static final String JWT_SECRET_KEY3 = dotenv.get("JWT_SECRET_KEY3");
    private static final Map<String, String> SECRET_KEY_SET = Map.of(
        "key1", JWT_SECRET_KEY1,
        "key2", JWT_SECRET_KEY2,
        "key3", JWT_SECRET_KEY3
    );
    private static final String[] KID_SET = SECRET_KEY_SET.keySet().toArray(new String[0]);
    private static Random randomIndex = new Random();

    /**
     * SECRET_KEY_SET 에서 랜덤한 KEY 가져오기
     *
     * @return kid와 key Pair
     */
    public static Pair<String, Key> getRandomKey() {
        String kid = KID_SET[randomIndex.nextInt(KID_SET.length)];
        String secretKey = SECRET_KEY_SET.get(kid);
        return Pair.of(kid, Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * kid로 Key찾기
     *
     * @param kid kid
     * @return Key
     */
    public static Key getKey(String kid) {
        String key = SECRET_KEY_SET.getOrDefault(kid, null);
        if (key == null) {
            return null;
        }
        return Keys.hmacShaKeyFor(key.getBytes(StandardCharsets.UTF_8));
    }
}