package com.gaebang.backend.domain.member.entity;

import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Embeddable
public class MemberBase {

  private String email;
  private String nickname;
  private String password;
  private String authority;

  public void changePassword(String encodedNewPassword) {
    this.password = encodedNewPassword;
  }

  public void changeNickname(String nickname){this.nickname = nickname;}
}
