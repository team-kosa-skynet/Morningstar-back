package com.gaebang.backend.domain.payment.entity;

import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "payment")
public class Payment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Long paymentId;

    @Column(unique = true)
    private String tid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PaymentStatus status;

    @Column(nullable = false)
    private Integer amount;

    @Column(nullable = false)
    private String partnerOrderId;

    @Column(nullable = false)
    private String itemName;

    @Column(name = "redirect_url")
    private String redirectUrl;

    @Column(name = "app_redirect_url")
    private String appRedirectUrl;

    @Column(name = "mobile_redirect_url")
    private String mobileRedirectUrl;

    @Column(name = "android_app_scheme")
    private String androidAppScheme;

    @Column(name = "ios_app_scheme")
    private String iosAppScheme;

    public void updateStatus(PaymentStatus status) {
        this.status = status;
    }
}