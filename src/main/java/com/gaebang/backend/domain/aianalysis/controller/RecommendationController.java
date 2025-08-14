package com.gaebang.backend.domain.aianalysis.controller;

import com.gaebang.backend.domain.aianalysis.recommendation.Answers;
import com.gaebang.backend.domain.aianalysis.recommendation.Model;
import com.gaebang.backend.domain.aianalysis.service.RecommendationService;
import com.gaebang.backend.global.util.ResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("api/ai-recommend")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;

    @PostMapping("")
    public ResponseEntity<ResponseDTO<List<Model>>> recommend(@RequestBody Answers answers) {
        ResponseDTO<List<Model>> response
                = recommendationService.recommendModels(answers);

        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }
}

