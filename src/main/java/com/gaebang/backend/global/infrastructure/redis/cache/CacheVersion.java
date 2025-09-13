package com.gaebang.backend.global.infrastructure.redis.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CacheVersion {
    private final StringRedisTemplate stringRedisTemplate;
    private static final String KEY = "ver:getBoards";

    public String current() {
        String v = stringRedisTemplate.opsForValue().get(KEY);
        return (v != null) ? v : "1";
    }
    
    public long bump() {
        Long nv = stringRedisTemplate.opsForValue().increment(KEY);
        return (nv != null) ? nv : 1L;
    }
}