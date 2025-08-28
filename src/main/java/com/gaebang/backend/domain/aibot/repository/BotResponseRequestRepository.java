package com.gaebang.backend.domain.aibot.repository;

import com.gaebang.backend.domain.aibot.entity.BotResponseRequest;
import com.gaebang.backend.domain.aibot.entity.RequestStatus;
import com.gaebang.backend.domain.aibot.entity.RequestType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BotResponseRequestRepository extends JpaRepository<BotResponseRequest, Long> {
    
    /**
     * 상태별 요청 목록 조회 (우선순위 + 생성일시 순)
     */
    List<BotResponseRequest> findByStatusOrderByPriorityAscCreatedAtAsc(RequestStatus status);
    
    /**
     * 특정 게시글에 대한 요청 조회
     */
    List<BotResponseRequest> findByBoardId(Long boardId);
    
    /**
     * 사용자별 요청 조회
     */
    List<BotResponseRequest> findByRequestedBy(Long requestedBy);
    
    /**
     * 게시글 ID와 요청 타입으로 조회
     */
    List<BotResponseRequest> findByBoardIdAndRequestType(Long boardId, RequestType requestType);
}