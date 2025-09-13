package com.gaebang.backend.domain.community.event;

import com.gaebang.backend.global.infrastructure.redis.cache.CacheVersion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class BoardChangedListener {
    private final CacheVersion cacheVersion;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChanged(BoardChangedEvent e) {
        long newVersion = cacheVersion.bump();
        log.info("🔄 Board cache invalidated - Board ID: {}, Type: {}, New Version: {}", 
                e.boardId(), e.type(), newVersion);
    }
}