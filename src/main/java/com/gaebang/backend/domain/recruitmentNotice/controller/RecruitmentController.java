package com.gaebang.backend.domain.recruitmentNotice.controller;

import com.gaebang.backend.domain.recruitmentNotice.dto.response.RecruitmentResponseDto;
import com.gaebang.backend.domain.recruitmentNotice.service.RecruitmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/recruitment")
@RequiredArgsConstructor
@Slf4j // 추가
public class RecruitmentController {

    private final RecruitmentService recruitmentService;

    @GetMapping("")
    public ResponseEntity<List<RecruitmentResponseDto>> getRecruitmentData() {
        List<RecruitmentResponseDto> recruitments = recruitmentService.getRecruitmentData();
        return ResponseEntity.ok(recruitments);
    }

}
