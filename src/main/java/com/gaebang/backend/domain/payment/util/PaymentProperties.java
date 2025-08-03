package com.gaebang.backend.domain.payment.util;

import lombok.Getter;
import org.springframework.stereotype.Component;
import io.github.cdimascio.dotenv.Dotenv;

@Component
@Getter
public class PaymentProperties {
    private static Dotenv dotenv = Dotenv.load();
    private final String secretkey = dotenv.get("KAKAO_SECRET_KEY_DEV");
    private final String cid = dotenv.get("KAKAO_CLIENT_CID");
    private final String readyUrl = "https://open-api.kakaopay.com/online/v1/payment/ready";
    private final String approveUrl = "https://open-api.kakaopay.com/online/v1/payment/approve";
    private final String success = dotenv.get("KAKAO_SUCCESS_URL");
    private final String fail = dotenv.get("KAKAO_FAIL_URL");
    private final String cancel = dotenv.get("KAKAO_CANCEL_URL");
}