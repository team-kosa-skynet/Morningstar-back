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

        List<Point> points = pointRepository.findPointsByMemberId(memberId);

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

    // 포인트 생성
    // 트랜잭션이 비효율적이다 -> 재시도 로직에서 같은 트랜잭션이 실행되기 때문에 이미 한 번 실패해버린 트랜잭션은
    // rollback이 일어나는데 그 상태에서 무의미한 재시도를 한다고 함. -> 그래도 데이터 불일치는 일어나지 않는다고 함.
    @Transactional
    public PointResponseDto createPoint(PointRequestDto pointRequestDto, PrincipalDetails principalDetails) {

        // 얘는 단순히 jwt에서 가져온 것이므로 현재 트랜잭션에서 가져온 member가 아니다!
//        Member member = principalDetails.getMember();
        Long memberId = principalDetails.getMember().getId();

        // 이렇게 하면 같은 트랜잭션 안에서 멤버를 가져온 것
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new UserNotFoundException());

        if (memberId == null) {
            throw new UserInvalidAccessException();
        }
        // 재시도 로직
        int retryCount = 0;
        int maxRetries = 3;

        while (retryCount < maxRetries) {
            try {
                // 최신 포인트 레코드를 한번에 조회
                Point latestPoint = pointRepository.findLatestPointByMemberId(member.getId())
                        .orElse(null);

                Integer nextVersion = (latestPoint == null) ? 1 : latestPoint.getVersion() + 1;
                Integer currentDepositSum = (latestPoint == null) ? 0 : latestPoint.getDepositSum();
                Integer currentWithdrawSum = (latestPoint == null) ? 0 : latestPoint.getWithdrawSum();
                // 포인트를 차감할 때 최신 버전의 남은 포인트를 계산해서 남은 포인트보다 사용하고자 하는 포인트가
                // 많은지 적은지 판단하는 부분
                if (pointRequestDto.amount() + currentDepositSum + currentWithdrawSum < 0) {
                    throw new InsufficientFundsException();
                }
                // 새 누적 합계 계산
                Integer newDepositSum = pointRequestDto.amount() > 0 ? currentDepositSum + pointRequestDto.amount() : currentDepositSum;
                Integer newWithdrawSum = pointRequestDto.amount() < 0 ? currentWithdrawSum + pointRequestDto.amount() : currentWithdrawSum;
                Point newPoint = pointRequestDto.toEntity(member, newDepositSum, newWithdrawSum, nextVersion);
                // 포인트 생성 후 디비에 저장
                pointRepository.save(newPoint);
                // 유저의 points 업데이트
                member.changePoint(newDepositSum + newWithdrawSum);
                // 유저의 상태 업데이트 버전
                PointTier pointTier = pointTierService.getTierByPoints(newDepositSum + newWithdrawSum);

                // 이것은 LazyInitializationException입니다. Hibernate의 지연 로딩(Lazy Loading) 문제로 발생하는 전형적인 오류입니다.
                // 이건 등급이 바뀌었을 때만 실행 될 수 있게 조정 => DB 효율성 상승을 위하여
                // member의 티어가 만약 null 값일 때 로직 추가
                if (member.getCurrentTier() == null ||
                        !Objects.equals(member.getCurrentTier().getTierOrder(), pointTier.getTierOrder())) {
                    member.changeTier(pointTier);
                }

                // 변경된 유저를 db에 저장
                memberRepository.save(member);

                // return 은 dto로
                return PointResponseDto.fromEntity(newPoint);

            } catch (DataIntegrityViolationException e) {
                retryCount++;
                if (retryCount >= maxRetries) {
                    throw new PointCreationRetryExhaustedException();
                }
            }
        }

        throw new PointCreationRetryExhaustedException();
    }
}
