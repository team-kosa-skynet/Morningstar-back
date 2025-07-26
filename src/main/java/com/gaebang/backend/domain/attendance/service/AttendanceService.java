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

                // 포인트 지급
                PointRequestDto pointRequestDto = PointRequestDto.builder()
                        .amount(50)
                        .type(PointType.ATTENDANCE)
                        .build();
                pointService.createPoint(pointRequestDto, principalDetails);

                return AttendanceResponseDto.fromEntity(newAttendance, true);
            }

        } catch (DataIntegrityViolationException e) {
            // 동시 요청으로 인한 중복 출석 시도 - 정상 처리
            log.info("Duplicate attendance attempt for member: {} on date: {}", member.getId(), today);
        }

        // 이미 출석한 경우 기존 출석 정보 반환
        Attendance existingAttendance = attendanceRepository
                .findByMemberIdAndAttendanceDate(member.getId(), today)
                .orElseThrow(() -> new RuntimeException("출석 정보를 찾을 수 없습니다"));

        return AttendanceResponseDto.fromEntity(existingAttendance, false);
    }

}
