package com.gaebang.backend.domain.point.service;

import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.point.dto.request.PointRequestDto;
import com.gaebang.backend.domain.point.dto.response.CurrentPointResponseDto;
import com.gaebang.backend.domain.point.dto.response.PointResponseDto;
import com.gaebang.backend.domain.point.entity.Point;
import com.gaebang.backend.domain.point.repository.PointRepository;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PointService {

    private final PointRepository pointRepository;
    private final MemberRepository memberRepository;

    // 포인트 내역 전체 조회
    public List<PointResponseDto> getAllPoint(PrincipalDetails principalDetails) {

        Long memberId = principalDetails.getMember().getId();

        if (memberId == null) {
            throw new RuntimeException(); // todo 예외처리는 0723일에 변경하자
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new RuntimeException()); // 예외처리는 0723일에 변경하자

        List<Point> points = pointRepository.findPointsByMemberId(member.getId());

        return points.stream()
                .map(point -> PointResponseDto.fromEntity(point))
                .collect(Collectors.toList());
    }



    // 현재 남아 있는 포인트 조회
    public CurrentPointResponseDto getCurrentPoint(PrincipalDetails principalDetails) {

        Long memberId = principalDetails.getMember().getId();

        if (memberId == null) {
            throw new RuntimeException(); // todo 예외처리는 0723일에 변경하자
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new RuntimeException()); // 예외처리는 0723일에 변경하자

        Point point = pointRepository.findLatestPointByMemberId(member.getId())
                .orElse(null);

        if (point == null) {
            // 포인트 내역이 없는 신규 사용자 - dto는 어차피 엔티티가 아니니 point
//            return CurrentPointResponseDto.builder()
//                    .pointId(null)
//                    .memberId(member.getId())
//                    .currentPoint(0)  // 기본값 0
//                    .build();

            // 포인트 내역이 없는 신규 사용자 - dto는 어차피 엔티티가 아니니 point
            return CurrentPointResponseDto.fromEntity(point, 0);
        }

//        return CurrentPointResponseDto.builder()
//                .pointId(point.getPointId())
//                .memberId(member.getId())
//                .currentPoint(point.getDepositSum() + point.getWithdrawSum())
//                .build();
        return CurrentPointResponseDto.fromEntity(point, point.getDepositSum() + point.getWithdrawSum());
    }

    // 포인트 생성
    public PointResponseDto createPoint(PointRequestDto pointRequestDto, PrincipalDetails principalDetails) {

        Long memberId = principalDetails.getMember().getId();

        if (memberId == null) {
            throw new RuntimeException(); // todo 예외처리는 0723일에 변경하자
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new RuntimeException()); // 예외처리는 0723일에 변경하자

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

                // 새 누적 합계 계산
                Integer newDepositSum = pointRequestDto.amount() > 0 ? currentDepositSum + pointRequestDto.amount() : currentDepositSum;
                Integer newWithdrawSum = pointRequestDto.amount() < 0 ? currentWithdrawSum + pointRequestDto.amount() : currentWithdrawSum;

                Point newPoint = pointRequestDto.toEntity(member, newDepositSum, newWithdrawSum, nextVersion);

                // 포인트 생성 후 디비에 저장
                pointRepository.save(newPoint);

                // return 은 dto로
                return PointResponseDto.fromEntity(newPoint);

            } catch (DataIntegrityViolationException e) {
                retryCount++;
                if (retryCount >= maxRetries) {
                    throw new RuntimeException("Failed after " + maxRetries + " attempts", e);
                }

                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); // 인터럽트 상태 복원
                    throw new RuntimeException("Thread was interrupted during retry", ie);
                }
            }
        }

        throw new RuntimeException("Failed after " + maxRetries + " attempts");
    }
}
