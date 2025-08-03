package com.gaebang.backend.domain.payment.controller;

import com.gaebang.backend.domain.payment.dto.request.PaymentReadyRequestDto;
import com.gaebang.backend.domain.payment.dto.response.PaymentApproveResponseDto;
import com.gaebang.backend.domain.payment.dto.response.PaymentReadyResponseDto;
import com.gaebang.backend.domain.payment.dto.response.PaymentStatusResponseDto;
import com.gaebang.backend.domain.payment.exception.PaymentExternalApiException;
import com.gaebang.backend.domain.payment.service.PaymentService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import com.gaebang.backend.global.util.ResponseDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;

    // 결제 요청
    @PostMapping("/ready")
    public ResponseEntity<ResponseDTO<PaymentReadyResponseDto>> readyToPayment(
            @RequestBody @Valid PaymentReadyRequestDto paymentReadyRequestDto,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        try {
            PaymentReadyResponseDto response = paymentService.paymentReady(paymentReadyRequestDto, principalDetails);
            ResponseDTO<PaymentReadyResponseDto> responseDTO = ResponseDTO.okWithData(response);
            return ResponseEntity.status(responseDTO.getCode()).body(responseDTO);
        } catch (PaymentExternalApiException e) {
            log.error("결제 준비 중 외부 API 오류: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("결제 준비 중 예상치 못한 오류: {}", e.getMessage());
            throw new PaymentExternalApiException("결제 준비 중 오류가 발생했습니다");
        }
    }

    @GetMapping("/cancel")
    public ResponseEntity<ResponseDTO<String>> cancel(
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        paymentService.cancelPayment(principalDetails);
        ResponseDTO<String> response = ResponseDTO.okWithData("결제가 취소되었습니다");
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    @GetMapping("/fail")
    public ResponseEntity<ResponseDTO<String>> fail(
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        paymentService.failPayment(principalDetails);
        ResponseDTO<String> response = ResponseDTO.okWithData("결제가 실패하였습니다");
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    @GetMapping("/status/{paymentId}")
    public ResponseEntity<ResponseDTO<PaymentStatusResponseDto>> getPaymentStatus(
            @PathVariable Long paymentId,
            @AuthenticationPrincipal PrincipalDetails principalDetails) {

        PaymentStatusResponseDto response = paymentService.getPaymentStatus(paymentId, principalDetails);
        ResponseDTO<PaymentStatusResponseDto> responseDTO = ResponseDTO.okWithData(response);
        return ResponseEntity.status(responseDTO.getCode()).body(responseDTO);
    }

}