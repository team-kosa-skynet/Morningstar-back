package com.gaebang.backend.global.springsecurity;

/**
 * @implNote OAuth2.0 제공자들 마다 응답 해주는 속성 세부 값이 달라서 생성한 공통 interface
 */
public interface OAuth2UserInfo {
    String getProvider();
    String getEmail();
    String getName();
    String getPhoneNumber();
    String getProfileImage();
}
