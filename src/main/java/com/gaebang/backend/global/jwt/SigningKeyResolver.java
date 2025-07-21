package com.gaebang.backend.global.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.SigningKeyResolverAdapter;

import java.security.Key;

/**
 * @author liyusang1
 * @implNote JwsHeader를 통해 Signature 검증에 필요한 Key를 가져오는 코드
 */
public class SigningKeyResolver extends SigningKeyResolverAdapter {

    public static SigningKeyResolver instance = new SigningKeyResolver();

    @Override
    public Key resolveSigningKey(JwsHeader jwsHeader, Claims claims) {
        String kid = jwsHeader.getKeyId();
        if (kid == null) {
            return null;
        }
        return JwtKey.getKey(kid);
    }
}