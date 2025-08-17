package com.gaebang.backend.domain.interview.controller;

import com.gaebang.backend.domain.interview.service.InterviewerAiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/interview")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewerAiService interviewerAiService;

    // 면접 시작 - 첫 질문 생성
    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> startInterview(@RequestBody Map<String, String> request) {
        try {
            String jobPosition = request.getOrDefault("jobPosition", "백엔드 개발자");
            String firstQuestion = interviewerAiService.generateFirstQuestion(jobPosition);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "question", firstQuestion,
                    "questionNumber", "1"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    // 답변 제출 후 다음 질문 생성
    @PostMapping("/answer")
    public ResponseEntity<Map<String, String>> submitAnswer(@RequestBody Map<String, String> request) {
        try {
            String answer = request.get("answer");
            String situation = request.getOrDefault("situation", "일반적인 기술 면접");

            if (answer == null || answer.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error",
                        "message", "답변을 입력해주세요"
                ));
            }

            String nextQuestion = interviewerAiService.generateNextQuestion(situation, answer);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "question", nextQuestion,
                    "previousAnswer", answer
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    // AI 서비스 연결 테스트
    @GetMapping("/test")
    public ResponseEntity<Map<String, String>> testAiConnection() {
        try {
            String testQuestion = interviewerAiService.generateFirstQuestion("테스트");
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "AI 서비스 연결 성공",
                    "testQuestion", testQuestion
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "AI 서비스 연결 실패: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/debug/env")
    public ResponseEntity<Map<String, String>> checkEnvironment() {
        return ResponseEntity.ok(Map.of(
                "apiKey", System.getenv("OPENAI_API_KEY") != null ? "있음" : "없음",
                "apiKeyLength", System.getenv("OPENAI_API_KEY") != null ?
                        String.valueOf(System.getenv("OPENAI_API_KEY").length()) : "0"
        ));
    }

}
