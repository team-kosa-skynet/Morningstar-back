package com.gaebang.backend.domain.point.controller;

import com.project.stock.investory.point.dto.request.PointRequestDto;
import com.project.stock.investory.point.dto.response.CurrentPointResponseDto;
import com.project.stock.investory.point.dto.response.PointResponseDto;
import com.project.stock.investory.point.service.PointService;
import com.project.stock.investory.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
    public ResponseEntity<List<PointResponseDto>> findAll(@AuthenticationPrincipal CustomUserDetails userDetails) {
        List<PointResponseDto> points = pointService.getAllPoint(userDetails);
        return ResponseEntity.ok(points);
    }

    // 현재 남아 있는 포인트 조회
    @GetMapping("/current")
    public ResponseEntity<CurrentPointResponseDto> findPoint(@AuthenticationPrincipal CustomUserDetails userDetails) {
        CurrentPointResponseDto point = pointService.getCurrentPoint(userDetails);
        return ResponseEntity.ok(point);
    }


    // test point 생성용
    @PostMapping("/")
    public ResponseEntity<PointResponseDto> create(
            @RequestBody PointRequestDto request,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {

        PointResponseDto response = pointService.createPoint(request, userDetails);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

}