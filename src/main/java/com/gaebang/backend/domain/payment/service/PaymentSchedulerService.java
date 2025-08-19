package com.gaebang.backend.domain.payment.service;

import com.gaebang.backend.domain.payment.entity.Payment;
import com.gaebang.backend.domain.payment.entity.PaymentStatus;
import com.gaebang.backend.domain.payment.repository.PaymentRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class PaymentSchedulerService {

    private final PaymentRepository paymentRepository;
    private static final int PAYMENT_TIMEOUT_MINUTES = 3; // 3분 타임아웃

//    @Scheduled(fixedRate = 60000) // 1분마다 실행
    public void processExpiredPayments() {
        LocalDateTime expiredTime = LocalDateTime.now().minusMinutes(PAYMENT_TIMEOUT_MINUTES);

        List<Payment> expiredPayments = paymentRepository.findExpiredPayments(
                PaymentStatus.READY, expiredTime
        );

        if (!expiredPayments.isEmpty()) {
            expiredPayments.forEach(payment -> {
                payment.updateStatus(PaymentStatus.TIMEOUT);
                log.info("결제 타임아웃 처리 완료 - TID: {}, Member ID: {}, Amount: {}",
                        payment.getTid(), payment.getMember().getId(), payment.getAmount());
            });

            log.info("총 {}건의 결제 타임아웃 처리 완료", expiredPayments.size());
        }
    }
}
