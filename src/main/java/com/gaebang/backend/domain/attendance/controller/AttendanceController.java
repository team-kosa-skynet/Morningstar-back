package com.gaebang.backend.domain.attendance.controller;

import com.gaebang.backend.domain.attendance.dto.response.AttendanceResponseDto;
import com.gaebang.backend.domain.attendance.service.AttendanceService;
import com.gaebang.backend.domain.member.dto.response.SignUpResponseDto;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import com.gaebang.backend.global.util.ResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/attendance")
@RequiredArgsConstructor
public class AttendanceController {

    private final AttendanceService attendanceService;

    @PostMapping()
    public ResponseEntity<ResponseDTO<AttendanceResponseDto>> addAttendance(@AuthenticationPrincipal PrincipalDetails principalDetails) {
        AttendanceResponseDto attendanceResponseDto = attendanceService.createAttendance(principalDetails);
        ResponseDTO<AttendanceResponseDto> response = ResponseDTO.okWithData(attendanceResponseDto);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

}
