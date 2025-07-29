package com.gaebang.backend.domain.community.repository;

import com.gaebang.backend.domain.community.entity.BoardReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BoardReportRepository extends JpaRepository<BoardReport, Long> {

    Optional<BoardReport> findByBoardIdAndMemberId(Long boardId, Long memberId);

    Page<BoardReport> findAll(Pageable pageable);
}
