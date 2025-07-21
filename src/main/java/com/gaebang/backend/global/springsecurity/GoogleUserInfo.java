package com.gaebang.backend.global.springsecurity;

import java.util.Map;

/**
 * @implNote OAuth2 구글 로그인 후 받아온 값에서 사용자 정보를 저장하기 위한 클래스
 */

public class GoogleUserInfo implements OAuth2UserInfo {

    private Map<String, Object> attributes;

    public GoogleUserInfo(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    @Override
    public String getName() {
        return (String) attributes.get("name");
    }

    @Override
    public String getPhoneNumber() {
        return null;
    }

    @Override
    public String getProfileImage() {
        return (String) attributes.get("picture");
    }

    @Override
    public String getEmail() {
        return (String) attributes.get("email") + "GoogleOAuth2";
    }

    @Override
    public String getProvider() {
        return "google";
    }
}
