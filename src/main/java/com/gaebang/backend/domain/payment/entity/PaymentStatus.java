package com.gaebang.backend.domain.payment.entity;

public enum PaymentStatus {
    READY,      // 결제 준비
    SUCCESS,    // 결제 성공
    FAIL,       // 결제 실패
    CANCEL,     // 결제 취소
    TIMEOUT     // 결제 타임아웃
}