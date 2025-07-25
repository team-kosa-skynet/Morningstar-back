package com.gaebang.backend.domain.community.repository;

import com.gaebang.backend.domain.community.entity.CommentReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentReportRepository extends JpaRepository<CommentReport, Long> {
}
