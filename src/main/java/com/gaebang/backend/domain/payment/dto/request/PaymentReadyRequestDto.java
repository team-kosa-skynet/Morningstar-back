package com.gaebang.backend.domain.payment.dto.request;

import com.gaebang.backend.domain.payment.dto.response.PaymentReadyResponseDto;
import com.gaebang.backend.domain.payment.entity.Payment;
import com.gaebang.backend.domain.payment.entity.PaymentStatus;
import com.gaebang.backend.domain.member.entity.Member;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jetbrains.annotations.NotNull;

public record PaymentReadyRequestDto(
        @NotNull
        @Min(value = 1000, message = "최소 후원 금액은 1,000원입니다")
        @Max(value = 1000000, message = "최대 후원 금액은 1,000,000원입니다")
        Integer amount
) {
    public Payment toEntity(Member member, PaymentReadyResponseDto paymentReadyResponseDto, String partnerOrderId) {
        return Payment.builder()
                .tid(paymentReadyResponseDto.tid())
                .member(member)
                .status(PaymentStatus.READY)
                .amount(amount)
                .partnerOrderId(partnerOrderId)
                .itemName("후원")
                .redirectUrl(paymentReadyResponseDto.next_redirect_pc_url())
                .appRedirectUrl(paymentReadyResponseDto.next_redirect_app_url())
                .mobileRedirectUrl(paymentReadyResponseDto.next_redirect_mobile_url())
                .androidAppScheme(paymentReadyResponseDto.android_app_scheme())
                .iosAppScheme(paymentReadyResponseDto.ios_app_scheme())
                .build();
    }
}