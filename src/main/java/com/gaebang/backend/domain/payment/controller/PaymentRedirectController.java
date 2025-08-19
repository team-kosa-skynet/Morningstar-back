package com.gaebang.backend.domain.payment.controller;

import com.gaebang.backend.domain.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/api/payment/redirect")
@RequiredArgsConstructor
@Slf4j
public class PaymentRedirectController {

    private final PaymentService paymentService;

    @GetMapping("/success")
    public String handlePaymentSuccess(
            @RequestParam("pg_token") String pgToken,
            @RequestParam("partner_order_id") String partnerOrderId) {
        log.info("카카오페이 결제 성공 리디렉션 - pg_token: {}, partner_order_id: {}", pgToken, partnerOrderId);

        try {
            paymentService.paymentApproveByPgToken(pgToken, partnerOrderId);
            return "redirect:https://gaebang.site/payment/redirect/success";
        } catch (Exception e) {
            return "redirect:https://gaebang.site/payment/redirect/fail";
        }
    }


    @GetMapping("/fail")
    public String handlePaymentFail() {
        log.info("카카오페이 결제 실패 리디렉션");
        return "redirect:https://gaebang.site/payment/redirect/fail";
    }

    @GetMapping("/cancel")
    public String handlePaymentCancel() {
        log.info("카카오페이 결제 취소 리디렉션");
        return "redirect:https://gaebang.site/payment/redirect/cancel";
    }
}

