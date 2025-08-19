package com.gaebang.backend.domain.question.gemini.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Gemini 질문 요청 DTO
 * 텍스트 질문과 함께 파일들을 첨부할 수 있습니다
 */
public class GeminiQuestionRequestDto {

        @NotBlank(message = "질문 내용을 입력해주세요")
        @Size(max = 10000, message = "질문은 10000자 이하로 입력해주세요")
        private String content;

        private List<MultipartFile> files; // 업로드할 파일들

        // 기본 생성자
        public GeminiQuestionRequestDto() {}

        // 전체 생성자
        public GeminiQuestionRequestDto(String content, List<MultipartFile> files) {
                this.content = content;
                this.files = files;
        }

        // Getter/Setter
        public String getContent() {
                return content;
        }

        public void setContent(String content) {
                this.content = content;
        }

        public List<MultipartFile> getFiles() {
                return files;
        }

        public void setFiles(List<MultipartFile> files) {
                this.files = files;
        }
}
