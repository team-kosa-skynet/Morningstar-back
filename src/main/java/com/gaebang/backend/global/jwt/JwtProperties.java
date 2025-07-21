package com.gaebang.backend.global.jwt;


public class JwtProperties {

    public static final int ACCESS_TOKEN_EXPIRATION_TIME = 1000 * 60 * 60 * 40; // 10분 -> 600000
    public static final int REFRESH_TOKEN_EXPIRATION_TIME = 1000 * 60 * 60 * 40;
    public static final String COOKIE_NAME = "JWT-AUTHENTICATION";
}