package com.gaebang.backend.domain.email.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record EmailVerifyCodeRequest(

        @NotBlank(message = "이메일은 필수 항목입니다.")
        @Email(message = "올바른 이메일 형식이어야 합니다.")
        String email,

        @NotBlank(message = "코드는 필수 항목입니다.")
        String code
) {

}