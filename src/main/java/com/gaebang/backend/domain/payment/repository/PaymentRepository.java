package com.gaebang.backend.domain.payment.repository;

import com.gaebang.backend.domain.payment.entity.Payment;
import com.gaebang.backend.domain.payment.entity.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByTid(String tid);

    Optional<Payment> findByMemberIdAndStatus(Long memberId, PaymentStatus status);


    @Query("SELECT p FROM Payment p WHERE p.status = :status AND p.createdAt < :expiredTime")
    List<Payment> findExpiredPayments(@Param("status") PaymentStatus status,
                                      @Param("expiredTime") LocalDateTime expiredTime);

    // 비관적 락을 위한 설정
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.partnerOrderId = :partnerOrderId AND p.status = :status")
    Optional<Payment> findByPartnerOrderIdAndStatusWithLock(@Param("partnerOrderId") String partnerOrderId,
                                                            @Param("status") PaymentStatus status);

}