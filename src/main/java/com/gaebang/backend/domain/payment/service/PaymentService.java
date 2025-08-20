package com.gaebang.backend.domain.payment.service;

import com.gaebang.backend.domain.payment.dto.request.PaymentReadyRequestDto;
import com.gaebang.backend.domain.payment.dto.response.PaymentApproveResponseDto;
import com.gaebang.backend.domain.payment.dto.response.PaymentStatusResponseDto;
import com.gaebang.backend.domain.payment.entity.Payment;
import com.gaebang.backend.domain.payment.entity.PaymentStatus;
import com.gaebang.backend.domain.payment.exception.PaymentAmountMismatchException;
import com.gaebang.backend.domain.payment.exception.PaymentExternalApiException;
import com.gaebang.backend.domain.payment.exception.PaymentNotFoundException;
import com.gaebang.backend.domain.payment.repository.PaymentRepository;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.exception.UserInvalidAccessException;
import com.gaebang.backend.domain.member.exception.UserNotFoundException;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.payment.dto.response.PaymentReadyResponseDto;
import com.gaebang.backend.domain.payment.util.PaymentProperties;
import com.gaebang.backend.domain.point.dto.request.PointRequestDto;
import com.gaebang.backend.domain.point.entity.PointType;
import com.gaebang.backend.domain.point.exception.PointCreationRetryExhaustedException;
import com.gaebang.backend.domain.point.service.PointService;
import com.gaebang.backend.global.exception.PermissionDeniedException;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.*;


@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentProperties paymentProperties;
    private final MemberRepository memberRepository;
    private final PaymentRepository paymentRepository;
    private final RestTemplate restTemplate;
    private final PointService pointService;

    private HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        String auth = "SECRET_KEY " + paymentProperties.getSecretkey();
        headers.set("Authorization", auth);
        headers.set("Content-Type", "application/json");
        return headers;
    }

    // 결제 준비 요청
    @Transactional
    public PaymentReadyResponseDto paymentReady(PaymentReadyRequestDto paymentReadyRequestDto, PrincipalDetails principalDetails) {

        Long memberId = principalDetails.getMember().getId();

        if (memberId == null) {
            throw new UserInvalidAccessException();
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new UserNotFoundException());

        // 기존 READY 상태 결제 확인 (먼저 확인만, 아직 변경하지 않음)
        Optional<Payment> existingPayment = paymentRepository
                .findByMemberIdAndStatus(member.getId(), PaymentStatus.READY);

        // 기존 결제가 있고 금액이 같으면 재활용
        if (existingPayment.isPresent()) {
            Payment payment = existingPayment.get();
            if (payment.getAmount().equals(paymentReadyRequestDto.amount())) {
                log.info("기존 결제 재활용 - 회원ID: {}, TID: {}, 금액: {}",
                        member.getId(), payment.getTid(), payment.getAmount());
                return PaymentReadyResponseDto.fromEntity(payment);
            }
        }

        // 새로운 결제 API 요청 데이터 준비
        Map<String, Object> parameters = new HashMap<>();
        String partnerOrderId = "ORDER_" + UUID.randomUUID().toString().replace("-", "");

        parameters.put("cid", paymentProperties.getCid());
        parameters.put("partner_order_id", partnerOrderId);
        parameters.put("partner_user_id", member.getId());
        parameters.put("item_name", "후원");
        parameters.put("quantity", "1");
        parameters.put("total_amount", paymentReadyRequestDto.amount().toString());
        int vatAmount = (int) (paymentReadyRequestDto.amount() * 0.1);
        parameters.put("vat_amount", String.valueOf(vatAmount));
        parameters.put("tax_free_amount", "0");
        parameters.put("approval_url", "https://gaebang.site/api/payment/redirect/success" + "?partner_order_id=" + partnerOrderId);
        parameters.put("fail_url", "https://gaebang.site/api/payment/redirect/fail");
        parameters.put("cancel_url", "https://gaebang.site/api/payment/redirect/cancel");

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(parameters, this.getHeaders());

        try {
            // 먼저 카카오페이 API 호출 (새 결제 생성 가능한지 확인)
            PaymentReadyResponseDto paymentReadyResponseDto = restTemplate.postForObject(
                    paymentProperties.getReadyUrl(),
                    requestEntity,
                    PaymentReadyResponseDto.class
            );

            // API 성공했으니 이제 기존 결제 정리 (금액이 다른 경우만)
            if (existingPayment.isPresent()) {
                Payment payment = existingPayment.get();
                if (!payment.getAmount().equals(paymentReadyRequestDto.amount())) {
                    payment.updateStatus(PaymentStatus.CANCEL);
                    log.info("새 결제 성공으로 기존 결제 취소 - 회원ID: {}, 기존금액: {}, 새금액: {}",
                            member.getId(), payment.getAmount(), paymentReadyRequestDto.amount());
                }
            }

            // 새 결제 저장
            Payment newPayment = paymentReadyRequestDto.toEntity(member, paymentReadyResponseDto, partnerOrderId);
            paymentRepository.save(newPayment);

            log.info("새 결제 생성 - 회원ID: {}, TID: {}, 금액: {}",
                    member.getId(), paymentReadyResponseDto.tid(), paymentReadyRequestDto.amount());

            return paymentReadyResponseDto;

        } catch (RestClientException e) {
            // API 실패시 기존 결제는 그대로 유지 (사용자가 기존 결제로 계속 진행 가능)
            log.error("카카오페이 결제 준비 API 호출 실패 - 회원ID: {}, 기존 결제 유지, 오류: {}",
                    member.getId(), e.getMessage());
            throw new PaymentExternalApiException("결제 준비 중 오류가 발생했습니다");
        }
    }

    // 결제 승인 요청
    @Transactional
    public PaymentApproveResponseDto paymentApproveByPgToken(String pgToken, String partnerOrderId) {
        // partnerOrderId로 결제 조회 (비관적 락 적용)
        Payment payment = paymentRepository.findByPartnerOrderIdAndStatusWithLock(partnerOrderId, PaymentStatus.READY)
                .orElseThrow(() -> new PaymentNotFoundException());

        Member member = payment.getMember();
        if (member == null) {
            throw new UserNotFoundException();
        }

        log.info("결제 승인 시작 - 회원ID: {}, TID: {}, 주문ID: {}",
                member.getId(), payment.getTid(), partnerOrderId);

        // 카카오페이 API 요청 데이터 준비
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("cid", paymentProperties.getCid());
        parameters.put("tid", payment.getTid());
        parameters.put("partner_order_id", payment.getPartnerOrderId());
        parameters.put("partner_user_id", payment.getMember().getId());
        parameters.put("pg_token", pgToken);

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(parameters, this.getHeaders());

        try {
            // 카카오페이 외부 API 호출
            PaymentApproveResponseDto paymentApproveResponseDto = restTemplate.postForObject(
                    paymentProperties.getApproveUrl(),
                    requestEntity,
                    PaymentApproveResponseDto.class);

            // 금액 검증
            if (!payment.getAmount().equals(paymentApproveResponseDto.amount().getTotal())) {
                payment.updateStatus(PaymentStatus.FAIL);
                log.error("결제 금액 불일치 - 요청: {}, 응답: {}, 회원ID: {}, 주문ID: {}",
                        payment.getAmount(), paymentApproveResponseDto.amount().getTotal(),
                        member.getId(), partnerOrderId);
                throw new PaymentAmountMismatchException("결제 금액이 일치하지 않습니다");
            }

            // 결제 성공 처리
            payment.updateStatus(PaymentStatus.SUCCESS);
            log.info("결제 승인 성공 - 회원ID: {}, TID: {}, 금액: {}, 주문ID: {}",
                    member.getId(), payment.getTid(), payment.getAmount(), partnerOrderId);

            // 포인트 적립 (재시도 로직 포함)
            try {
                PrincipalDetails principalDetails = new PrincipalDetails(member);
                PointRequestDto pointRequestDto = PointRequestDto.builder()
                        .amount(payment.getAmount())
                        .type(PointType.SPONSORSHIP)
                        .build();

                pointService.createPoint(pointRequestDto, principalDetails);
                log.info("포인트 적립 성공 - 회원ID: {}, 금액: {}, 주문ID: {}",
                        member.getId(), payment.getAmount(), partnerOrderId);

            } catch (PointCreationRetryExhaustedException e) {
                // 3번 재시도 후에도 실패한 경우 - 결제는 성공 유지
                log.error("포인트 적립 완전 실패 (3회 재시도 후) - 회원ID: {}, 금액: {}, 주문ID: {}, 오류: {}",
                        member.getId(), payment.getAmount(), partnerOrderId, e.getMessage());
                // 고객 서비스팀에 알림 등의 후속 처리 가능
            } catch (Exception e) {
                // 다른 예상치 못한 포인트 적립 오류
                log.error("포인트 적립 실패 - 회원ID: {}, 금액: {}, 주문ID: {}, 오류: {}",
                        member.getId(), payment.getAmount(), partnerOrderId, e.getMessage());
            }

            return paymentApproveResponseDto;

        } catch (RestClientException e) {
            // 카카오페이 API 호출 실패 - 치명적 오류
            payment.updateStatus(PaymentStatus.FAIL);
            log.error("카카오페이 승인 API 호출 실패 - 회원ID: {}, TID: {}, 주문ID: {}, 오류: {}",
                    member.getId(), payment.getTid(), partnerOrderId, e.getMessage());
            throw new PaymentExternalApiException("결제 승인 중 오류가 발생했습니다");
        }
    }


    @Transactional
    public void cancelPayment(PrincipalDetails principalDetails) {

        Long memberId = principalDetails.getMember().getId();

        if (memberId == null) {
            throw new UserInvalidAccessException();
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new UserNotFoundException());

        Payment payment = paymentRepository.findByMemberIdAndStatus(member.getId(), PaymentStatus.READY)
                .orElseThrow(() -> new PaymentNotFoundException());
        payment.updateStatus(PaymentStatus.CANCEL);
        log.info("결제 취소 - 회원ID: {}, 결제ID: {}", principalDetails.getMember().getId(), payment.getPaymentId());
    }

    @Transactional
    public void failPayment(PrincipalDetails principalDetails) {

        Long memberId = principalDetails.getMember().getId();

        if (memberId == null) {
            throw new UserInvalidAccessException();
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new UserNotFoundException());

        Payment payment = paymentRepository.findByMemberIdAndStatus(member.getId(), PaymentStatus.READY)
                .orElseThrow(() -> new PaymentNotFoundException());
        payment.updateStatus(PaymentStatus.FAIL);
        log.info("결제 실패 처리 - 회원ID: {}, 결제ID: {}", principalDetails.getMember().getId(), payment.getPaymentId());
    }

    @Transactional(readOnly = true)
    public PaymentStatusResponseDto getPaymentStatus(Long paymentId, PrincipalDetails principalDetails) {
        Member member = memberRepository.findById(principalDetails.getMember().getId())
                .orElseThrow(() -> new UserNotFoundException());

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException());

        // 본인의 결제만 조회 가능
        if (!payment.getMember().getId().equals(member.getId())) {
            throw new PermissionDeniedException();
        }

        return new PaymentStatusResponseDto(
                payment.getPaymentId(),
                payment.getStatus().name(),
                payment.getAmount(),
                payment.getItemName(),
                payment.getCreatedAt().toString()
        );
    }
}