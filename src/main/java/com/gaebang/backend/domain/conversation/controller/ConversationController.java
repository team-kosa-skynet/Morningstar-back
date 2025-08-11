package com.gaebang.backend.domain.conversation.controller;

import com.gaebang.backend.domain.conversation.dto.request.CreateConversationRequestDto;
import com.gaebang.backend.domain.conversation.dto.request.UpdateConversationTitleRequestDto;
import com.gaebang.backend.domain.conversation.dto.response.ConversationDetailResponseDto;
import com.gaebang.backend.domain.conversation.dto.response.ConversationListResponseDto;
import com.gaebang.backend.domain.conversation.dto.response.CreateConversationResponseDto;
import com.gaebang.backend.domain.conversation.service.ConversationService;
import com.gaebang.backend.global.springsecurity.PrincipalDetails;
import com.gaebang.backend.global.util.ResponseDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 대화방 관리 REST API Controller
 * 사이드바의 채팅방 목록, 대화방 생성/수정/삭제, 상세 조회 등을 담당
 * LLM 질문 API는 별도의 Question Controller들에서 처리
 */
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
@Slf4j
public class ConversationController {

    private final ConversationService conversationService;

    /**
     * 새로운 대화방을 생성합니다
     * 사용자가 "새 채팅" 버튼을 클릭했을 때 호출되는 API
     *
     * @param request 대화방 생성 요청 (제목은 선택사항)
     * @param principalDetails 인증된 사용자 정보
     * @return 생성된 대화방 정보 (ID, 제목, 생성시간)
     */
    @PostMapping
    public ResponseEntity<ResponseDTO<CreateConversationResponseDto>> createConversation(
            @Valid @RequestBody CreateConversationRequestDto request,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        log.info("대화방 생성 요청 - 사용자 ID: {}, 제목: {}",
                principalDetails.getMember().getId(), request.title());

        CreateConversationResponseDto responseDto = conversationService.createConversation(
                principalDetails.getMember().getId(),
                request
        );

        ResponseDTO<CreateConversationResponseDto> response = ResponseDTO.okWithData(responseDto);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 사용자의 모든 대화방 목록을 조회합니다 (사이드바용)
     * ChatGPT처럼 왼쪽 사이드바에 표시할 채팅방 목록을 가져옴
     *
     * @param principalDetails 인증된 사용자 정보
     * @return 대화방 목록 (제목, 마지막 수정시간, 메시지 개수 포함)
     */
    @GetMapping
    public ResponseEntity<ResponseDTO<ConversationListResponseDto>> getConversationList(
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        log.info("대화방 목록 조회 요청 - 사용자 ID: {}", principalDetails.getMember().getId());

        ConversationListResponseDto responseDto = conversationService.getConversationList(
                principalDetails.getMember().getId()
        );

        ResponseDTO<ConversationListResponseDto> response = ResponseDTO.okWithData(responseDto);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 특정 대화방의 상세 정보와 전체 메시지 히스토리를 조회합니다
     * 사용자가 사이드바에서 특정 대화방을 클릭했을 때 호출
     *
     * @param conversationId 조회할 대화방 ID
     * @param principalDetails 인증된 사용자 정보
     * @return 대화방 상세 정보와 모든 메시지들
     */
    @GetMapping("/{conversationId}")
    public ResponseEntity<ResponseDTO<ConversationDetailResponseDto>> getConversationDetail(
            @PathVariable Long conversationId,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        log.info("대화방 상세 조회 요청 - 대화방 ID: {}, 사용자 ID: {}",
                conversationId, principalDetails.getMember().getId());

        ConversationDetailResponseDto responseDto = conversationService.getConversationDetail(
                conversationId,
                principalDetails.getMember().getId()
        );

        ResponseDTO<ConversationDetailResponseDto> response = ResponseDTO.okWithData(responseDto);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 대화방 제목을 수정합니다
     * 사용자가 대화방 제목 옆의 편집 버튼을 클릭했을 때 사용
     *
     * @param conversationId 수정할 대화방 ID
     * @param request 새로운 제목 정보
     * @param principalDetails 인증된 사용자 정보
     * @return 수정된 대화방 정보
     */
    @PutMapping("/{conversationId}/title")
    public ResponseEntity<ResponseDTO<CreateConversationResponseDto>> updateConversationTitle(
            @PathVariable Long conversationId,
            @Valid @RequestBody UpdateConversationTitleRequestDto request,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        log.info("대화방 제목 수정 요청 - 대화방 ID: {}, 사용자 ID: {}, 새 제목: {}",
                conversationId, principalDetails.getMember().getId(), request.title());

        CreateConversationResponseDto responseDto = conversationService.updateConversationTitle(
                conversationId,
                principalDetails.getMember().getId(),
                request
        );

        ResponseDTO<CreateConversationResponseDto> response = ResponseDTO.okWithData(responseDto);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 대화방을 삭제합니다 (소프트 삭제)
     * 실제로 DB에서 삭제하지 않고 비활성화 처리
     * 사용자가 대화방 삭제 버튼을 클릭했을 때 호출
     *
     * @param conversationId 삭제할 대화방 ID
     * @param principalDetails 인증된 사용자 정보
     * @return 삭제 완료 메시지
     */
    @DeleteMapping("/{conversationId}")
    public ResponseEntity<ResponseDTO<Void>> deleteConversation(
            @PathVariable Long conversationId,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        log.info("대화방 삭제 요청 - 대화방 ID: {}, 사용자 ID: {}",
                conversationId, principalDetails.getMember().getId());

        conversationService.deleteConversation(
                conversationId,
                principalDetails.getMember().getId()
        );

        ResponseDTO<Void> response = ResponseDTO.okWithMessage("대화방이 성공적으로 삭제되었습니다.");
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 대화방 검색 (선택적 기능)
     * 사용자가 많은 대화방을 가지고 있을 때 제목으로 검색
     *
     * @param keyword 검색할 키워드
     * @param principalDetails 인증된 사용자 정보
     * @return 검색된 대화방 목록
     */
    @GetMapping("/search")
    public ResponseEntity<ResponseDTO<ConversationListResponseDto>> searchConversations(
            @RequestParam String keyword,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        log.info("대화방 검색 요청 - 사용자 ID: {}, 키워드: {}",
                principalDetails.getMember().getId(), keyword);

        // 실제로는 ConversationService에 searchConversations 메서드 추가 필요
        // 여기서는 기본 목록 조회로 대체 (실제 구현시 검색 로직 추가)
        ConversationListResponseDto responseDto = conversationService.getConversationList(
                principalDetails.getMember().getId()
        );

        ResponseDTO<ConversationListResponseDto> response = ResponseDTO.okWithData(responseDto);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 사용자의 대화 통계 정보 조회 (선택적 기능)
     * 총 대화방 수, 총 메시지 수 등의 통계 제공
     *
     * @param principalDetails 인증된 사용자 정보
     * @return 대화 통계 정보
     */
    @GetMapping("/statistics")
    public ResponseEntity<ResponseDTO<java.util.Map<String, Object>>> getConversationStatistics(
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        log.info("대화 통계 조회 요청 - 사용자 ID: {}", principalDetails.getMember().getId());

        // 간단한 통계 정보 제공 (실제로는 ConversationService에서 구현)
        java.util.Map<String, Object> statistics = new java.util.HashMap<>();
        statistics.put("totalConversations", 0); // 실제 구현시 서비스에서 조회
        statistics.put("totalMessages", 0);
        statistics.put("averageMessagesPerConversation", 0.0);

        ResponseDTO<java.util.Map<String, Object>> response = ResponseDTO.okWithData(statistics);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }
}
