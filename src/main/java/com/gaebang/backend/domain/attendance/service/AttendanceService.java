package com.gaebang.backend.domain.attendance.service;

import com.gaebang.backend.domain.attendance.dto.response.AttendanceResponseDto;
import com.gaebang.backend.domain.attendance.entity.Attendance;
import com.gaebang.backend.domain.attendance.repository.AttendanceRepository;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.exception.UserInvalidAccessException;
import com.gaebang.backend.domain.member.exception.UserNotFoundException;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.point.dto.request.PointRequestDto;
import com.gaebang.backend.domain.point.entity.PointType;
import com.gaebang.backend.domain.point.service.PointService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final PointService pointService;
    private final MemberRepository memberRepository;

    // 해당 날짜에 첫 로그인시 출석 테이블에 로그인 로그 저장 및 포인트 지급
    @Transactional
    public AttendanceResponseDto createAttendance(PrincipalDetails principalDetails) {
        Long memberId = principalDetails.getMember().getId();

        // 1단계: 출석 처리 (핵심 비즈니스 로직)
        AttendanceResponseDto attendanceResult = processAttendanceRecord(principalDetails, memberId);
        
        // 2단계: 포인트 지급 (별도 트랜잭션 - 실패해도 출석은 유지)
        if (attendanceResult.isNewAttendance()) {
            processPointRewardInSeparateTransaction(principalDetails);
        }
        
        return attendanceResult;
    }

    /**
     * 출석 기록 처리 (출석 도메인의 핵심 로직)
     * 포인트 지급과 독립적으로 처리
     */
    @Transactional
    public AttendanceResponseDto processAttendanceRecord(PrincipalDetails principalDetails, Long memberId) {
        // 이렇게 하면 같은 트랜잭션 안에서 멤버를 가져온 것
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new UserNotFoundException());

        if (member == null) {
            throw new UserInvalidAccessException();
        }

        LocalDate today = LocalDate.now();

        try {
            // 출석 기록이 없다면 새로 생성
            if (!attendanceRepository.existsByMemberIdAndAttendanceDate(member.getId(), today)) {
                Attendance newAttendance = Attendance.builder()
                        .member(member)
                        .attendanceDate(today)
                        .build();

                attendanceRepository.save(newAttendance);
                log.info("새 출석 기록 생성 완료 - 회원ID: {}, 날짜: {}", member.getId(), today);

                return AttendanceResponseDto.fromEntity(newAttendance, true);
            }

        } catch (DataIntegrityViolationException e) {
            // 동시 요청으로 인한 중복 출석 시도 - 정상 처리
            log.info("Duplicate attendance attempt for member: {} on date: {}", member.getId(), today);
            // 새로운 트랜잭션으로 안전하게 조회
            return getExistingAttendanceInNewTransaction(member.getId(), today);
        }

        // 이미 출석한 경우 기존 출석 정보 반환 (새로운 트랜잭션으로 안전 조회)
        return getExistingAttendanceInNewTransaction(member.getId(), today);
    }

    /**
     * 포인트 지급 처리 (별도 트랜잭션)
     * 출석 처리와 독립적으로 실행되어 실패해도 출석은 유지됨
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processPointRewardInSeparateTransaction(PrincipalDetails principalDetails) {
        PointRequestDto pointRequestDto = PointRequestDto.builder()
                .amount(100)
                .type(PointType.ATTENDANCE)
                .build();
        
        try {
            pointService.createPoint(pointRequestDto, principalDetails);
            log.info("출석 포인트 지급 완료 - 회원ID: {}, 포인트: 100", 
                    principalDetails.getMember().getId());
            
        } catch (Exception e) {
            // 포인트 지급 실패 - 1회 재시도
            log.error("출석 포인트 지급 실패 - 회원ID: {}, 오류: {}", 
                    principalDetails.getMember().getId(), e.getMessage());
            
            try {
                log.info("포인트 지급 재시도 중 - 회원ID: {}", principalDetails.getMember().getId());
                Thread.sleep(1000); // 1초 대기
                
                pointService.createPoint(pointRequestDto, principalDetails);
                log.info("포인트 재시도 성공 - 회원ID: {}, 포인트: 100", 
                        principalDetails.getMember().getId());
                
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.error("포인트 재시도 중 인터럽트 발생 - 회원ID: {}", 
                        principalDetails.getMember().getId());
                
            } catch (Exception retryException) {
                log.error("포인트 재시도도 실패 - 회원ID: {}, 오류: {}", 
                        principalDetails.getMember().getId(), retryException.getMessage());
                // 최종 실패 - 운영팀 개입 필요 또는 추후 배치 처리 필요
                // TODO: 실패 케이스 별도 기록 또는 알림 시스템 연동
            }
        }
    }

    /**
     * 새로운 트랜잭션으로 기존 출석 정보를 안전하게 조회
     * 트랜잭션 타이밍 이슈로 인한 RuntimeException 방지
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public AttendanceResponseDto getExistingAttendanceInNewTransaction(Long memberId, LocalDate date) {
        Attendance attendance = attendanceRepository
                .findByMemberIdAndAttendanceDate(memberId, date)
                .orElseThrow(() -> new RuntimeException("출석 정보를 찾을 수 없습니다"));
        
        log.info("기존 출석 정보 조회 완료 - 회원ID: {}, 날짜: {}", memberId, date);
        return AttendanceResponseDto.fromEntity(attendance, false);
    }

}
