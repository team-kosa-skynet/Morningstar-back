package com.gaebang.backend.domain.payment.dto.response;

import com.gaebang.backend.domain.payment.entity.Payment;
import com.gaebang.backend.global.util.DataFormatter;

public record PaymentReadyResponseDto(
        Long paymentId,
        String tid,
        String next_redirect_app_url,
        String next_redirect_mobile_url,
        String next_redirect_pc_url,
        String android_app_scheme,
        String ios_app_scheme,
        String created_at
) {

    public static PaymentReadyResponseDto fromEntity(Payment payment) {
        return new PaymentReadyResponseDto(
                payment.getPaymentId(),
                payment.getTid(),
                payment.getAppRedirectUrl(),
                payment.getMobileRedirectUrl(),
                payment.getRedirectUrl(),
                payment.getAndroidAppScheme(),
                payment.getIosAppScheme(),
                DataFormatter.getFormattedCreatedAtWithTime(payment.getCreatedAt())
        );
    }

}