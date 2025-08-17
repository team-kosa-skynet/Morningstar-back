package com.gaebang.backend.domain.ai.controller;

import com.gaebang.backend.domain.ai.dto.AiNewsResponseDto;
import com.gaebang.backend.domain.ai.service.AiNewsService;
import com.gaebang.backend.global.util.ResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ai-news")
@RequiredArgsConstructor
public class AiNewsController {

    private final AiNewsService service;

    @PostMapping("/refresh")
    public ResponseDTO<List<AiNewsResponseDto>> refreshLatestNews() throws Exception {
        return service.refreshLatestNews();
    }

    @PostMapping("/summarize/{id}")
    public ResponseDTO<AiNewsResponseDto> summarizeNews(@PathVariable Long id) throws Exception {
        return service.summarizeNews(id);
    }

    @GetMapping("/history")
    public ResponseDTO<List<AiNewsResponseDto>> getNewsHistory() {
        return service.getNewsHistory();
    }
}
