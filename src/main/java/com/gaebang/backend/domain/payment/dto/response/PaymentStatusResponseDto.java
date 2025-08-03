package com.gaebang.backend.domain.payment.dto.response;

public record PaymentStatusResponseDto(
        Long paymentId,
        String status,
        Integer amount,
        String itemName,
        String createdAt
) {}
