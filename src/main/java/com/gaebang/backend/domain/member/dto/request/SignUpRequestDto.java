package com.gaebang.backend.domain.member.dto.request;

import com.gaebang.backend.domain.member.entity.Member;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record SignUpRequestDto(

    @Pattern(regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$", message = "이메일 형식이 유효하지 않습니다")
    @NotNull(message = "email은 필수값입니다")
    String email,

    @NotNull(message = "password는 필수값입니다")
    String password
) {

    public Member toEntity(String encodedPassword, String name) {
        return Member.builder()
            .email(email)
            .nickname(name)
            .password(encodedPassword)
            .authority("ROLE_USER")
            .build();
    }
}
