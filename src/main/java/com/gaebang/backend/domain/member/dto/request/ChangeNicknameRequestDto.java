package com.gaebang.backend.domain.member.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChangeNicknameRequestDto(

        @NotBlank(message = "닉네임은 필수값입니다.")
        @Size(max = 20, message = "닉네임은 최대 20자까지 입력 가능합니다.")
        @Pattern(regexp = "^[a-zA-Z0-9가-힣]*$", message = "닉네임은 한글, 영문, 숫자만 사용 가능합니다.")
        String nickname
) {
}
