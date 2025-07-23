//package com.gaebang.backend.domain.pointTier.controller;
//
//import com.gaebang.backend.domain.pointTier.dto.response.PointTierResponseDto;
//import com.gaebang.backend.domain.pointTier.service.PointTierService;
//import com.gaebang.backend.global.springsecurity.PrincipalDetails;
//import lombok.RequiredArgsConstructor;
//import org.springframework.http.ResponseEntity;
//import org.springframework.security.core.annotation.AuthenticationPrincipal;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RestController;
//
//@RestController
//@RequestMapping("/point-tier")
//@RequiredArgsConstructor
//public class PointTierController {
//
//    private final PointTierService pointTierService;
//
//    // 현재 남아 있는 포인트 조회
//    @GetMapping("/")
//    public ResponseEntity<PointTierResponseDto> findPoint(@AuthenticationPrincipal PrincipalDetails principalDetails) {
//        PointTierResponseDto pointTier = pointTierService.getCurrentPointTier(principalDetails);
//        return ResponseEntity.ok(pointTier);
//    }
//
//}
