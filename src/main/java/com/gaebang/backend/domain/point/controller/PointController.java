package com.gaebang.backend.domain.point.controller;

import com.gaebang.backend.domain.point.dto.request.PointRequestDto;
import com.gaebang.backend.domain.point.dto.response.CurrentPointResponseDto;
import com.gaebang.backend.domain.point.dto.response.PointResponseDto;
import com.gaebang.backend.domain.point.service.PointService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import com.gaebang.backend.global.util.ResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/point")
@RequiredArgsConstructor
public class PointController {

    private final PointService pointService;

    // 포익트 내역 전체 조회
    @GetMapping("/history")
    public ResponseEntity<ResponseDTO<List<PointResponseDto>>> findAll(@AuthenticationPrincipal PrincipalDetails principalDetails) {
        List<PointResponseDto> pointResponseDtos = pointService.getAllPoint(principalDetails);
        ResponseDTO<List<PointResponseDto>> response = ResponseDTO.okWithData(pointResponseDtos);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    // 현재 남아 있는 포인트 조회
    @GetMapping("/current")
    public ResponseEntity<ResponseDTO<CurrentPointResponseDto>> findPoint(@AuthenticationPrincipal PrincipalDetails principalDetails) {
        CurrentPointResponseDto currentPointResponseDto = pointService.getCurrentPoint(principalDetails);
        ResponseDTO<CurrentPointResponseDto> response = ResponseDTO.okWithData(currentPointResponseDto);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }


    // test point 생성용
    @PostMapping
    public ResponseEntity<ResponseDTO<PointResponseDto>> create(
            @RequestBody PointRequestDto request,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {

        PointResponseDto pointResponseDto = pointService.createPoint(request, principalDetails);
        ResponseDTO<PointResponseDto> response = ResponseDTO.okWithData(pointResponseDto);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

}