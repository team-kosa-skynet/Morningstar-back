package com.gaebang.backend.domain.question.common.util;

import com.gaebang.backend.domain.conversation.dto.request.FileAttachmentDto;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.exception.UserInvalidAccessException;
import com.gaebang.backend.domain.member.exception.UserNotFoundException;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.question.common.service.FileProcessingService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class QuestionServiceUtils {

    public static Member validateAndGetMember(PrincipalDetails principalDetails, MemberRepository memberRepository) {
        Long memberId = principalDetails.getMember().getId();
        if (memberId == null) {
            throw new UserInvalidAccessException();
        }
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new UserNotFoundException());
    }

    public static List<FileAttachmentDto> processFiles(List<MultipartFile> files, FileProcessingService fileProcessingService) {
        if (files == null || files.isEmpty()) {
            return Collections.emptyList();
        }

        return files.stream()
                .map(file -> {
                    Map<String, Object> processedFile = fileProcessingService.processFile(file);

                    return new FileAttachmentDto(
                            (String) processedFile.get("fileName"),
                            (String) processedFile.get("type"),
                            (Long) processedFile.get("fileSize"),
                            (String) processedFile.get("mimeType")
                    );
                })
                .collect(Collectors.toList());
    }

    public static void setupEmitterCallbacks(SseEmitter emitter, String serviceName) {
        emitter.onTimeout(() -> {
            log.warn("{} 스트리밍 타임아웃", serviceName);
            emitter.complete();
        });

        emitter.onCompletion(() -> {
            log.info("{} 스트리밍 완료", serviceName);
        });

        emitter.onError((throwable) -> {
            log.error("{} 스트리밍 에러", serviceName, throwable);
        });
    }

    public static void handleStreamError(SseEmitter emitter, Exception e) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data("AI 응답 생성 중 오류가 발생했습니다."));
            emitter.completeWithError(e);
        } catch (IOException ioException) {
            log.error("에러 전송 실패", ioException);
        }
    }

    public static String buildContentWithExtractedFiles(String userText, List<MultipartFile> files, FileProcessingService fileProcessingService) {
        if (files == null || files.isEmpty()) {
            return userText;
        }

        StringBuilder contentBuilder = new StringBuilder(userText);

        for (MultipartFile file : files) {
            try {
                Map<String, Object> processedFile = fileProcessingService.processFile(file);
                String fileType = (String) processedFile.get("type");
                String fileName = (String) processedFile.get("fileName");

                if ("text".equals(fileType)) {
                    String extractedText = (String) processedFile.get("extractedText");
                    
                    contentBuilder.append("\n\n너는 파일을 해석하는 전문가야. 다음 파일의 내용을 분석하고 사용자의 질문에 답변해줘.\n\n");
                    contentBuilder.append("=== 파일 전체 내용 시작 ===\n\n");
                    contentBuilder.append("파일명: ").append(fileName).append("\n\n");
                    contentBuilder.append(extractedText);
                    contentBuilder.append("\n\n=== 파일 전체 내용 끝 ===\n");
                    
                } else if ("image".equals(fileType)) {
                    contentBuilder.append("\n\n--- 파일: ").append(fileName).append(" ---\n");
                    contentBuilder.append("업로드된 이미지: ").append(fileName);
                    contentBuilder.append("\n\n[이미지 분석 안내: 이 이미지는 현재 질문에서만 직접 분석됩니다. ");
                    contentBuilder.append("향후 이 이미지에 대한 추가 질문이 있을 경우, 이번 답변에서 제공된 분석 결과를 참고해주세요.]");
                    contentBuilder.append("\n--- 파일 끝 ---");
                }
                
            } catch (Exception e) {
                log.error("파일 내용 추출 실패: {}", file.getOriginalFilename(), e);
                contentBuilder.append("\n\n--- 파일: ").append(file.getOriginalFilename()).append(" ---\n");
                contentBuilder.append("[파일 처리 실패]");
                contentBuilder.append("\n--- 파일 끝 ---");
            }
        }

        return contentBuilder.toString();
    }

    /**
     * 대화 히스토리에서 가장 최근의 사용자 메시지를 가져옴
     */
    public static String getLastUserMessage(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> message = messages.get(i);
            if ("user".equals(message.get("role"))) {
                return (String) message.get("content");
            }
        }
        return "";
    }
}