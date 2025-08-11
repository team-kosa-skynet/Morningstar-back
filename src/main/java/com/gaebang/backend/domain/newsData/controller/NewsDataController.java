package com.gaebang.backend.domain.newsData.controller;

import com.gaebang.backend.domain.newsData.dto.response.NewsDataResponseDTO;
import com.gaebang.backend.domain.newsData.service.NewsDataService;
import com.gaebang.backend.global.util.ResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
@Slf4j // 추가
public class NewsDataController {

    private final NewsDataService newsDataService;

    @GetMapping("")
    public ResponseEntity<ResponseDTO<List<NewsDataResponseDTO>>> getNewsData() {  // void -> String으로 변경
        List<NewsDataResponseDTO> newsData = newsDataService.getNewsData();
        ResponseDTO<List<NewsDataResponseDTO>> response = ResponseDTO.okWithData(newsData);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }
}
