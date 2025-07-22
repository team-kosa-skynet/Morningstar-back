package com.gaebang.backend.domain.member.dto.request;

import com.gaebang.backend.domain.member.entity.Member;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignUpRequestDto(

    @Pattern(regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$", message = "이메일 형식이 유효하지 않습니다")
    @NotNull(message = "email은 필수값입니다")
    String email,

    @NotNull(message = "password는 필수값입니다")
    String password,

    @NotBlank(message = "닉네임은 필수값입니다.")
    @Size(max = 10, message = "닉네임은 최대 10자까지 입력 가능합니다.")
    @Pattern(regexp = "^[a-zA-Z0-9가-힣]*$", message = "닉네임은 한글, 영문, 숫자만 사용 가능합니다.")
    String nickname
) {

    public Member toEntity(String encodedPassword) {
        return Member.builder()
            .email(email)
            .nickname(nickname)
            .password(encodedPassword)
            .authority("ROLE_USER")
            .build();
    }
}
