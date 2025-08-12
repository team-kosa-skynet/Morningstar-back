package com.gaebang.backend.domain.recruitmentNotice.repository;

import com.gaebang.backend.domain.recruitmentNotice.entity.Recruitment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RecruitmentRepository extends JpaRepository<Recruitment, Long> {

    // 중복 체크용 (서비스에서 사용)
    // boolean existsByLink(String link);
    @Query(value = "SELECT COUNT(*) FROM recruitment WHERE link = ?1", nativeQuery = true)
    Long countExistingByLink(String link);

    // 만료일이 오늘 이후인 채용공고만 조회 (최신순 100개)
    List<Recruitment> findTop100ByExpirationDateAfterOrderByPubDateDesc(LocalDateTime currentDate);
}
