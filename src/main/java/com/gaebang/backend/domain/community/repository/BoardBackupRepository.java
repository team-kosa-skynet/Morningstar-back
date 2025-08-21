package com.gaebang.backend.domain.community.repository;

import com.gaebang.backend.domain.community.entity.BoardBackup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoardBackupRepository extends JpaRepository<BoardBackup, Long> {
    BoardBackup findByBoardId(Long boardId);
}