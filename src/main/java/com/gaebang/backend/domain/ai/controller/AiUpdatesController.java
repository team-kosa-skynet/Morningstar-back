package com.gaebang.backend.domain.ai.controller;

import com.gaebang.backend.domain.ai.entity.AiUpdate;
import com.gaebang.backend.domain.ai.service.AiUpdatesService;
import com.gaebang.backend.global.util.ResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-updates")
@RequiredArgsConstructor
public class AiUpdatesController {

    private final AiUpdatesService service;

    @GetMapping("/latest")
    public ResponseDTO<AiUpdate> getLatest() throws Exception {
        return service.getLatestAiUpdates();
    }
}
