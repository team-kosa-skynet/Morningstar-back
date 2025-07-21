package com.gaebang.backend.global.springsecurity;

import java.util.Map;

/**
 * @implNote OAuth2 카카오 로그인 후 받아온 값에서 사용자 정보를 저장하기 위한 클래스
 */

public class KakaoUserInfo implements OAuth2UserInfo {

    private Map<String, Object> attributes;

    public KakaoUserInfo(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    @Override
    public String getName() {
        // Kakao의 닉네임은 properties 안에 있습니다.
        Map<String, Object> properties = (Map<String, Object>) attributes.get("properties");
        return (String) properties.get("nickname");
    }

    @Override
    public String getPhoneNumber() {
        return null;
    }

    @Override
    public String getEmail() {
        // Kakao의 이메일은 kakao_account 안에 있습니다.
        Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
        return (String) kakaoAccount.get("email") + "KaKaoOAuth2";
    }

    @Override
    public String getProvider() {
        return "kakao";
    }

    @Override
    public String getProfileImage() {
        // Kakao의 프로필 이미지는 properties 안에 있습니다.
        Map<String, Object> properties = (Map<String, Object>) attributes.get("properties");
        return (String) properties.get("profile_image");
    }
}
