package com.gaebang.backend.domain.point.service;

import com.project.stock.investory.alarm.exception.AuthenticationRequiredException;
import com.project.stock.investory.comment.exception.UserNotFoundException;
import com.project.stock.investory.point.dto.request.PointRequestDto;
import com.project.stock.investory.point.dto.response.CurrentPointResponseDto;
import com.project.stock.investory.point.dto.response.PointResponseDto;
import com.project.stock.investory.point.entity.Point;
import com.project.stock.investory.point.repository.PointRepository;
import com.project.stock.investory.security.CustomUserDetails;
import com.project.stock.investory.user.entity.User;
import com.project.stock.investory.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PointService {

    private final PointRepository pointRepository;
    private final UserRepository userRepository;

    // 포인트 내역 전체 조회
    public List<PointResponseDto> getAllPoint(CustomUserDetails userDetails) {
        if (userDetails.getUserId() == null) {
            throw new AuthenticationRequiredException();
        }

        User user = userRepository.findById(userDetails.getUserId())
                .orElseThrow(() -> new UserNotFoundException()); // 예외처리

        List<Point> points = pointRepository.findPointsByUserId(user.getUserId());

        return points.stream()
                .map(point -> PointResponseDto.builder()
                        .pointId(point.getPointId())
                        .userId(user.getUserId())
                        .amount(point.getAmount())
                        .type(point.getType())
                        .depositSum(point.getDepositSum())
                        .withdrawSum(point.getWithdrawSum())
                        .date(point.getDate())
                        .build())
                .collect(Collectors.toList());
    }



    // 현재 남아 있는 포인트 조회
    public CurrentPointResponseDto getCurrentPoint(CustomUserDetails userDetails) {

        if (userDetails.getUserId() == null) {
            throw new AuthenticationRequiredException();
        }

        User user = userRepository.findById(userDetails.getUserId())
                .orElseThrow(() -> new UserNotFoundException()); // 예외처리

        Point point = pointRepository.findLatestPointByUserId(user.getUserId())
                .orElse(null);

        if (point == null) {
            // 포인트 내역이 없는 신규 사용자 - dto는 어차피 엔티티가 아니니 point
            return CurrentPointResponseDto.builder()
                    .pointId(null)
                    .userId(userDetails.getUserId())
                    .currentPoint(0)  // 기본값 0
                    .build();
        }

        return CurrentPointResponseDto.builder()
                .pointId(point.getPointId())
                .userId(userDetails.getUserId())
                .currentPoint(point.getDepositSum() + point.getWithdrawSum())
                .build();
    }

    // 포인트 생성
    public PointResponseDto createPoint(PointRequestDto request, CustomUserDetails userDetails) {

        if (userDetails.getUserId() == null) {
            throw new AuthenticationRequiredException();
        }

        User user = userRepository.findById(userDetails.getUserId())
                .orElseThrow(() -> new UserNotFoundException()); // 예외처리

        // 재시도 로직
        int retryCount = 0;
        int maxRetries = 3;

        while (retryCount < maxRetries) {
            try {
                // 최신 포인트 레코드를 한번에 조회
                Point latestPoint = pointRepository.findLatestPointByUserId(user.getUserId())
                        .orElse(null);

                Integer nextVersion = (latestPoint == null) ? 1 : latestPoint.getVersion() + 1;
                Integer currentDepositSum = (latestPoint == null) ? 0 : latestPoint.getDepositSum();
                Integer currentWithdrawSum = (latestPoint == null) ? 0 : latestPoint.getWithdrawSum();

                // 새 누적 합계 계산
                Integer newDepositSum = request.getAmount() > 0 ? currentDepositSum + request.getAmount() : currentDepositSum;
                Integer newWithdrawSum = request.getAmount() < 0 ? currentWithdrawSum + request.getAmount() : currentWithdrawSum;

                Point point = Point.builder()
                        .user(user)
                        .amount(request.getAmount())
                        .type(request.getType())
                        .depositSum(newDepositSum)
                        .withdrawSum(newWithdrawSum)
                        .date(LocalDateTime.now())
                        .version(nextVersion)
                        .build();

                // 포인트 생성 후 디비에 저장
                pointRepository.save(point);

                // return 은 dto로
                return PointResponseDto.builder()
                        .pointId(point.getPointId())
                        .userId(point.getUser().getUserId())
                        .amount(point.getAmount())
                        .type(point.getType())
                        .depositSum(point.getDepositSum())
                        .withdrawSum(point.getWithdrawSum())
                        .date(point.getDate())
                        .build();

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
