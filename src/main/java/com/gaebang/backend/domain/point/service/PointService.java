package com.gaebang.backend.domain.point.service;

import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.exception.UserInvalidAccessException;
import com.gaebang.backend.domain.member.exception.UserNotFoundException;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.point.dto.request.PointRequestDto;
import com.gaebang.backend.domain.point.dto.response.CurrentPointResponseDto;
import com.gaebang.backend.domain.point.dto.response.PointResponseDto;
import com.gaebang.backend.domain.point.entity.Point;
import com.gaebang.backend.domain.point.exception.InsufficientFundsException;
import com.gaebang.backend.domain.point.exception.PointCreationRetryExhaustedException;
import com.gaebang.backend.domain.point.repository.PointRepository;
import com.gaebang.backend.domain.pointTier.entity.PointTier;
import com.gaebang.backend.domain.pointTier.service.PointTierService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PointService {

    private final PointRepository pointRepository;
    private final MemberRepository memberRepository;
    private final PointTierService pointTierService;
    // 포인트 내역 전체 조회
    public List<PointResponseDto> getAllPoint(PrincipalDetails principalDetails) {
        Long memberId = principalDetails.getMember().getId();

        if (memberId == null) {
            throw new UserInvalidAccessException();
        }

        List<Point> points = pointRepository.findPointsByMemberIdOrderByVersionDesc(memberId);

        return points.stream()
                .map(point -> PointResponseDto.fromEntity(point))
                .collect(Collectors.toList());
    }

    // 현재 남아 있는 포인트 조회
    public CurrentPointResponseDto getCurrentPoint(PrincipalDetails principalDetails) {
        Long memberId = principalDetails.getMember().getId();

        if (memberId == null) {
            throw new UserInvalidAccessException();
        }

        Point point = pointRepository.findLatestPointByMemberId(memberId)
                .orElse(null);

        if (point == null) {
            // 포인트 내역이 없는 신규 사용자
            return CurrentPointResponseDto.fromEntity(memberId, 0);
        }
        return CurrentPointResponseDto.fromEntity(point, point.getDepositSum() + point.getWithdrawSum());
    }

    // 포인트 생성 (재시도 로직 포함)
    public PointResponseDto createPoint(PointRequestDto pointRequestDto, PrincipalDetails principalDetails) {
        Long memberId = principalDetails.getMember().getId();
        if (memberId == null) {
            throw new UserInvalidAccessException();
        }

        // 재시도 로직 - 각 재시도마다 새로운 트랜잭션 생성
        int retryCount = 0;
        int maxRetries = 3;

        while (retryCount < maxRetries) {
            try {
                return createPointInternal(pointRequestDto, memberId);
                
            } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException e) {
                retryCount++;
                log.warn("포인트 생성 충돌 발생 - 회원ID: {}, 재시도: {}/{}, 오류: {}", 
                        memberId, retryCount, maxRetries, e.getClass().getSimpleName());
                
                if (retryCount >= maxRetries) {
                    throw new PointCreationRetryExhaustedException();
                }
                
                // 재시도 전 잠시 대기 (동시성 충돌 완화)
                try {
                    Thread.sleep(50L * retryCount); // 50ms, 100ms, 150ms 간격으로 대기
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new PointCreationRetryExhaustedException();
                }
            }
        }

        throw new PointCreationRetryExhaustedException();
    }

    /**
     * 포인트 생성 내부 로직 (각 재시도마다 새로운 트랜잭션에서 실행)
     */
    @Transactional
    private PointResponseDto createPointInternal(PointRequestDto pointRequestDto, Long memberId) {
        // 트랜잭션 안에서 Member 조회
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new UserNotFoundException());

        // 최신 포인트 레코드를 한번에 조회
        Point latestPoint = pointRepository.findLatestPointByMemberId(member.getId())
                .orElse(null);

        Integer nextVersion = (latestPoint == null) ? 1 : latestPoint.getVersion() + 1;
        Integer currentDepositSum = (latestPoint == null) ? 0 : latestPoint.getDepositSum();
        Integer currentWithdrawSum = (latestPoint == null) ? 0 : latestPoint.getWithdrawSum();
        
        // 포인트 잔액 검증
        if (pointRequestDto.amount() + currentDepositSum + currentWithdrawSum < 0) {
            throw new InsufficientFundsException();
        }

        // 새 누적 합계 계산
        Integer newDepositSum = pointRequestDto.amount() > 0 ? currentDepositSum + pointRequestDto.amount() : currentDepositSum;
        Integer newWithdrawSum = pointRequestDto.amount() < 0 ? currentWithdrawSum + pointRequestDto.amount() : currentWithdrawSum;
        
        Point newPoint = pointRequestDto.toEntity(member, newDepositSum, newWithdrawSum, nextVersion);
        
        // 포인트 생성 후 디비에 저장
        pointRepository.save(newPoint);
        
        // 계산된 포인트 값
        Integer calculatedPoint = newDepositSum + newWithdrawSum;
        
        // Member의 현재 포인트를 원자적으로 업데이트 (데이터 정합성 보장)
        member.changePoint(calculatedPoint);
        
        // 포인트 기반 티어 업데이트 (필요한 경우에만)
        updateMemberTierIfNeeded(member, calculatedPoint);

        log.info("포인트 생성 완료 - 회원ID: {}, 금액: {}, 총 포인트: {}", 
                member.getId(), pointRequestDto.amount(), calculatedPoint);

        return PointResponseDto.fromEntity(newPoint);
    }

    /**
     * 포인트 변경에 따른 회원 티어 업데이트 (필요한 경우에만)
     * 티어 변경이 필요한 경우에만 DB 업데이트를 수행하여 효율성 향상
     */
    private void updateMemberTierIfNeeded(Member member, Integer newPointTotal) {
        try {
            PointTier newTier = pointTierService.getTierByPoints(newPointTotal);
            
            // 현재 티어가 없거나 티어가 변경된 경우에만 업데이트
            if (member.getCurrentTier() == null ||
                    !Objects.equals(member.getCurrentTier().getTierOrder(), newTier.getTierOrder())) {

                // 새로운 티어 업데이트
                member.changeTier(newTier);
            }
            
        } catch (Exception e) {
            log.error("회원 티어 업데이트 실패 - 회원ID: {}, 포인트: {}, 오류: {}", 
                    member.getId(), newPointTotal, e.getMessage());
            // 티어 업데이트 실패해도 포인트 적립은 성공 처리
        }
    }
}
