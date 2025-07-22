package com.gaebang.backend.domain.pointTier.controller;//package com.project.stock.investory.pointTier.controller;
//
//import com.project.stock.investory.pointTier.dto.response.PointTierResponseDto;
//import com.project.stock.investory.pointTier.service.PointTierService;
//import com.project.stock.investory.security.CustomUserDetails;
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
//    public ResponseEntity<PointTierResponseDto> findPoint(@AuthenticationPrincipal CustomUserDetails userDetails) {
//        PointTierResponseDto pointTier = pointTierService.getCurrentPointTier(userDetails);
//        return ResponseEntity.ok(pointTier);
//    }
//
//}
