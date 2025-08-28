package com.gaebang.backend.domain.aibot.repository;

import com.gaebang.backend.domain.aibot.entity.BotResponse;
import com.gaebang.backend.domain.aibot.entity.ResponseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BotResponseRepository extends JpaRepository<BotResponse, Long> {
    
    /**
     * 게시글에 대한 기존 AI 답변 조회
     */
    Optional<BotResponse> findByBoardId(Long boardId);
    
    /**
     * 게시글에 특정 상태의 AI 답변이 있는지 확인
     */
    boolean existsByBoardIdAndStatusIn(Long boardId, List<ResponseStatus> statuses);
    
    /**
     * 특정 상태의 응답 개수 (일일 한도 체크용)
     */
    long countByStatusAndCreatedAtBetween(ResponseStatus status, LocalDateTime start, LocalDateTime end);
    
    /**
     * 실패한 응답들 조회 (재시도용)
     */
    List<BotResponse> findByStatusAndCreatedAtAfter(ResponseStatus status, LocalDateTime after);
    
    /**
     * 게시글에 이미 완료된 AI 답변이 있는지 확인
     */
    @Query("SELECT COUNT(br) > 0 FROM BotResponse br WHERE br.boardId = :boardId AND br.status = 'POSTED'")
    boolean existsPostedResponseForBoard(@Param("boardId") Long boardId);
}