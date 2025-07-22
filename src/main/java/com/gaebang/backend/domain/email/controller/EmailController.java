package com.gaebang.backend.domain.email.controller;

import com.gaebang.backend.domain.email.dto.request.EmailRequest;
import com.gaebang.backend.domain.email.dto.request.EmailVerifyCodeRequest;
import com.gaebang.backend.domain.email.dto.response.EmailVerifiedResponse;
import com.gaebang.backend.domain.email.service.EmailService;
import com.gaebang.backend.global.util.ResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/email")
public class EmailController {

    private final EmailService emailService;

    @PostMapping("/confirmation")
    public ResponseEntity<ResponseDTO<Void>> mailConfirm(@RequestBody EmailRequest emailRequest)
            throws Exception {
        emailService.sendVerificationEmail(emailRequest.email());

        ResponseDTO<Void> response = ResponseDTO.okWithMessage("인증 이메일이 성공적으로 전송되었습니다.");
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    @PostMapping("/verify")
    public ResponseEntity<ResponseDTO<EmailVerifiedResponse>> verifyEmail(
            @RequestBody EmailVerifyCodeRequest emailVerifyCodeRequest) {

        ResponseDTO<EmailVerifiedResponse> response = emailService.verifyEmailCode(
                emailVerifyCodeRequest.email(), emailVerifyCodeRequest.code());
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }
}