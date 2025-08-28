package com.gaebang.backend.domain.aibot.scheduler;

import com.gaebang.backend.domain.aibot.entity.BotResponseRequest;
import com.gaebang.backend.domain.aibot.entity.RequestStatus;
import com.gaebang.backend.domain.aibot.entity.RequestType;
import com.gaebang.backend.domain.aibot.repository.BotResponseRequestRepository;
import com.gaebang.backend.domain.aibot.service.AiBotService;
import com.gaebang.backend.domain.community.entity.Board;
import com.gaebang.backend.domain.community.entity.BoardCategory;
import com.gaebang.backend.domain.community.entity.ModerationStatus;
import com.gaebang.backend.domain.community.repository.BoardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "aibot.enabled", havingValue = "true")
public class AiBotRecoveryScheduler {

    private final BoardRepository boardRepository;
    private final BotResponseRequestRepository botResponseRequestRepository;
    private final AiBotService aiBotService;

    @Value("${aibot.recovery.enabled:true}")
    private boolean recoveryEnabled;

    @Value("${aibot.recovery.lookback-hours:2}")
    private int lookbackHours;

    /**
     * 30분마다 실행되는 장애 복구 스케줄러
     * 놓친 질문 게시글에 대한 AI 답변을 복구 처리
     */
    @Scheduled(fixedRate = 30 * 60 * 1000) // 30분 = 30 * 60 * 1000ms
    public void recoverMissedAiResponses() {
        if (!recoveryEnabled) {
            log.debug("[AI봇 복구] 복구 기능이 비활성화되어 있습니다.");
            return;
        }

        log.info("[AI봇 복구] 놓친 AI 답변 복구 작업을 시작합니다.");
        
        try {
            LocalDateTime cutoffTime = LocalDateTime.now().minusHours(lookbackHours);
            
            // 1. 최근 2시간 내 승인된 질문 게시글 조회
            List<Board> approvedQuestionBoards = boardRepository
                    .findByCategoryAndModerationStatusAndCreatedAtAfter(
                            BoardCategory.QUESTION, 
                            ModerationStatus.APPROVED,
                            cutoffTime
                    );

            log.debug("[AI봇 복구] {}시간 내 승인된 질문 게시글 {}개 발견", lookbackHours, approvedQuestionBoards.size());

            int processedCount = 0;
            int recoveredCount = 0;

            for (Board board : approvedQuestionBoards) {
                processedCount++;
                
                // 2. 해당 게시글에 대한 AI 요청 이력 확인
                List<BotResponseRequest> existingRequests = botResponseRequestRepository
                        .findByBoardIdAndRequestType(board.getId(), RequestType.AUTO);

                // 3. AI 요청 이력이 없거나 모두 취소된 경우 복구 처리
                boolean needsRecovery = existingRequests.isEmpty() || 
                        existingRequests.stream().allMatch(req -> 
                                req.getStatus() == RequestStatus.CANCELLED
                        );

                if (needsRecovery && isEligibleForAiResponse(board)) {
                    log.info("[AI봇 복구] 게시글 복구 처리 시작 - ID: {}, 제목: '{}'", 
                             board.getId(), 
                             board.getTitle().length() > 30 ? 
                                 board.getTitle().substring(0, 30) + "..." : 
                                 board.getTitle());
                    
                    // 4. 비동기로 AI 답변 생성 처리
                    aiBotService.generateAnswerAsync(board.getId());
                    recoveredCount++;
                }
            }

            log.info("[AI봇 복구] 복구 작업 완료 - 처리된 게시글: {}개, 복구된 게시글: {}개", 
                     processedCount, recoveredCount);

        } catch (Exception e) {
            log.error("[AI봇 복구] 복구 작업 중 오류 발생", e);
        }
    }

    /**
     * AI 답변 생성 대상 게시글인지 확인
     */
    private boolean isEligibleForAiResponse(Board board) {
        // 1. 질문 카테고리인지 확인
        if (board.getCategory() != BoardCategory.QUESTION) {
            return false;
        }

        // 2. 검열 승인 상태인지 확인
        if (board.getModerationStatus() != ModerationStatus.APPROVED) {
            return false;
        }

        // 3. 삭제되지 않은 게시글인지 확인
        if ("Y".equals(board.getDeleteYn())) {
            return false;
        }

        // 4. 최소 내용 길이 확인 (5글자 이상)
        if (board.getContent() == null || board.getContent().trim().length() < 5) {
            return false;
        }

        return true;
    }
}