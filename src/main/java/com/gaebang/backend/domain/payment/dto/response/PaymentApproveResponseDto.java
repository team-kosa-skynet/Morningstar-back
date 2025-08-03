package com.gaebang.backend.domain.payment.dto.response;

public record PaymentApproveResponseDto(
        String aid,
        String tid,
        String cid,
        String sid,
        String partner_order_id,
        String partner_user_id,
        String payment_method_type,
        Amount amount,
        String item_name,
        String item_code,
        int quantity,
        String created_at,
        String approved_at,
        String payload
) {

}