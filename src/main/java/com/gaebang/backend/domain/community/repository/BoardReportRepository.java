package com.gaebang.backend.domain.community.repository;

import com.gaebang.backend.domain.community.entity.BoardReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoardReportRepository extends JpaRepository<BoardReport, Long> {
}
