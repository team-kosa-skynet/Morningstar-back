package com.gaebang.backend.domain.ai.controller;

import com.gaebang.backend.domain.ai.dto.AIUpdateNewsResponseDto;
import com.gaebang.backend.domain.ai.service.AiUpdatesService;
import com.gaebang.backend.global.util.ResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-updates")
@RequiredArgsConstructor
public class AiUpdatesController {

    private final AiUpdatesService service;

    @GetMapping("/generate-news")
    public void generateAIUpdateNews() throws Exception {
        service.getLatestAiUpdates();
    }

    @GetMapping("")
    public ResponseEntity<ResponseDTO<Page<AIUpdateNewsResponseDto>>> getAIUpdatesNewsList(
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        ResponseDTO<Page<AIUpdateNewsResponseDto>> response = service.getAIUpdatesNewsList(pageable);

        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }
}
