package com.gaebang.backend.domain.recruitmentNotice.repository;

import com.gaebang.backend.domain.recruitmentNotice.entity.Recruitment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RecruitmentRepository extends JpaRepository<Recruitment, Long> {

    // 중복 체크용 (서비스에서 사용)
    // boolean existsByLink(String link);
    @Query(value = "SELECT COUNT(*) FROM recruitment WHERE link = ?1", nativeQuery = true)
    Long countExistingByLink(String link);

    // 만료일이 오늘 이후이고, 등록일이 2개월 이내인 채용공고만 조회
    @Query("SELECT r FROM Recruitment r WHERE r.expirationDate > :currentDate AND r.pubDate > :twoMonthsAgo ORDER BY r.pubDate DESC")
    List<Recruitment> findByExpirationDateAfterAndPubDateAfterOrderByPubDateDesc(@Param("currentDate") LocalDateTime currentDate, @Param("twoMonthsAgo") LocalDateTime twoMonthsAgo);

    // 배치로 기존 링크들 확인 (N+1 문제 해결)
    @Query("SELECT r.link FROM Recruitment r WHERE r.link IN :links")
    List<String> findExistingLinks(@Param("links") List<String> links);
}
