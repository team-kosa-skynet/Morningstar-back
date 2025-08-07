package com.gaebang.backend.domain.newsData.controller;

import com.gaebang.backend.domain.newsData.dto.NewsDataResponseDTO;
import com.gaebang.backend.domain.newsData.service.NewsDataService;
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
    public ResponseEntity<List<NewsDataResponseDTO>> getNewsData() {  // void -> String으로 변경
        List<NewsDataResponseDTO> newsData = newsDataService.getNewsData();
        return ResponseEntity.ok(newsData);
    }

//    // api로 데이터를 잘 받고 db에 저장하는지 확인하는 컨트롤러
//    @GetMapping("/test")
//    public String getNewsDataApi() {  // void -> String으로 변경
//        System.out.println("Controller 호출됨!");
//        newsDataService.fetchAndSaveNews();
//        return "response";
//    }
}
