package com.gaebang.backend.domain.question.rag.controller;

import com.gaebang.backend.domain.question.rag.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/conversations/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;

    // 1) 엑셀 업로드 -> Qdrant 업서트
    @PostMapping("/uploadExcel")
    public String uploadExcel(@RequestParam("file") MultipartFile file) {
        try {
            ragService.uploadExcel(file.getInputStream());
            return "Excel data is uploaded & embedded successfully!";
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    // 2) 질문 -> 검색 (단순 JSON 결과)
    @GetMapping("/ask")
    public String askQuestion(@RequestParam String question, @RequestParam(defaultValue = "3") int topK) {
        return ragService.askQuestion(question, topK);
    }
}