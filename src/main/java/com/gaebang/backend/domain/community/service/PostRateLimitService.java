package com.gaebang.backend.domain.community.service;

import com.gaebang.backend.domain.community.exception.PostRateLimitExceededException;
import com.gaebang.backend.domain.community.repository.BoardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@RequiredArgsConstructor
@Service
public class PostRateLimitService {

    private final BoardRepository boardRepository;

    @Value("${rate-limit.post.max-count:3}")
    private int maxPostCount;

    @Value("${rate-limit.post.window-minutes:5}")
    private int windowMinutes;

    public void validatePostRateLimit(Long memberId) {
        LocalDateTime windowStart = LocalDateTime.now().minusMinutes(windowMinutes);
        
        int recentPostCount = countRecentPosts(memberId, windowStart);
        
        if (recentPostCount >= maxPostCount) {
            log.warn("게시글 작성 한도 초과 - 사용자 ID: {}, 최근 {}분간 게시글 수: {}", 
                     memberId, windowMinutes, recentPostCount);
            throw new PostRateLimitExceededException();
        }
        
        log.debug("게시글 작성 가능 - 사용자 ID: {}, 최근 {}분간 게시글 수: {}/{}", 
                  memberId, windowMinutes, recentPostCount, maxPostCount);
    }

    private int countRecentPosts(Long memberId, LocalDateTime windowStart) {
        return boardRepository.countByMemberIdAndDeleteYnAndCreatedAtAfter(memberId, windowStart);
    }
}