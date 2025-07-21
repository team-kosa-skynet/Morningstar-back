package com.gaebang.backend.global.jwt;

import com.gaebang.backend.domain.member.entity.Member;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;

@Component
@RequiredArgsConstructor
public class JwtProvider {

    /**
     * @implNote 토큰에서 유저 정보를 추출하는 코드
     */
    public String getEmail(String token) {
        // jwtToken에서 email을 찾습니다.
        return Jwts.parserBuilder()
            .setSigningKeyResolver(SigningKeyResolver.instance)
            .build()
            .parseClaimsJws(token)
            .getBody()
            .getSubject();
    }

    /**
     * member로 토큰 생성 HEADER : alg, kid PAYLOAD : sub, iat, exp SIGNATURE : JwtKey.getRandomKey로 구한
     * Secret Key로 HS512 해시
     *
     * @param member 유저
     * @return jwt token
     */
    public String createToken(Member member) {
        Claims claims = Jwts.claims().setSubject(member.getMemberBase().getEmail()); // subject
        Date now = new Date(); // 현재 시간
        Pair<String, Key> key = JwtKey.getRandomKey();
        // JWT Token 생성
        return Jwts.builder()
            .setClaims(claims) // 정보 저장
            .setIssuedAt(now) // 토큰 발행 시간 정보
            .setExpiration(
                new Date(now.getTime() + JwtProperties.ACCESS_TOKEN_EXPIRATION_TIME)) // 토큰 만료 시간 설정
            .setHeaderParam(JwsHeader.KEY_ID, key.getFirst()) // kid
            .signWith(key.getSecond()) // signature
            .compact();
    }

    public String createRefreshToken(String email) {
        Claims claims = Jwts.claims().setSubject(email); // subject
        Date now = new Date(); // 현재 시간
        Pair<String, Key> key = JwtKey.getRandomKey();
        // JWT Token 생성
        String refreshToken = Jwts.builder()
            .setClaims(claims) // 정보 저장
            .setIssuedAt(now) // 토큰 발행 시간 정보
            .setExpiration(new Date(
                now.getTime() + JwtProperties.REFRESH_TOKEN_EXPIRATION_TIME)) // 토큰 만료 시간 설정
            .setHeaderParam(JwsHeader.KEY_ID, key.getFirst()) // kid
            .signWith(key.getSecond()) // signature
            .compact();

        return refreshToken;
    }
}
