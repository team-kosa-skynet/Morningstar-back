package com.gaebang.backend.domain.newsData.repository;

import com.gaebang.backend.domain.newsData.entity.NewsData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NewsDataRepository extends JpaRepository<NewsData, Long> {

    // 중복 체크용 (서비스에서 사용)
    @Query(value = "SELECT COUNT(*) FROM news WHERE link = ?1", nativeQuery = true)
    Long countExistingByLink(String link);

    // 특정 기간 뉴스 조회
    List<NewsData> findByPubDateBetween(LocalDateTime start, LocalDateTime end);

    // 최신 뉴스 조회
    @Query("SELECT n FROM NewsData n WHERE n.isActive = 1 ORDER BY n.pubDate DESC")
    List<NewsData> findAllActiveNewsOrderByPubDateDesc();

    // 최신 인기 뉴스 조회
    @Query("SELECT n FROM NewsData n WHERE n.isActive = 1 AND n.isPopular = 1 ORDER BY n.pubDate DESC")
    List<NewsData> findAllActiveNewsAndPopularNewsOrderByPubDateDesc();

    // 특정 날짜 범위 뉴스 조회
    @Query("SELECT n FROM NewsData n WHERE n.pubDate >= :startDate AND n.pubDate < :endDate ORDER BY n.pubDate DESC")
    List<NewsData> findNewsByDateRange(@Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

    // isPopular를 1로 설정하는 메서드
    @Modifying
    @Transactional
    @Query("UPDATE NewsData n SET n.isPopular = 1 WHERE n.newsId = :newsId")
    void markAsPopular(@Param("newsId") Long newsId);

    // isActive를 0로 설정하는 메서드
    @Modifying
    @Transactional
    @Query("UPDATE NewsData n SET n.isActive = 0 WHERE n.newsId = :newsId")
    void markAsActive(@Param("newsId") Long newsId);

    // imageUrl 업데이트 메서드
    @Modifying
    @Transactional
    @Query("UPDATE NewsData n SET n.imageUrl = :imageUrl WHERE n.newsId = :newsId")
    void updateImageUrl(@Param("newsId") Long newsId, @Param("imageUrl") String imageUrl);


    // 이미지가 없는 활성 뉴스 조회 메서드
    @Query("SELECT n FROM NewsData n WHERE (n.imageUrl IS NULL OR n.imageUrl = '') AND n.isActive = 1 ORDER BY n.pubDate DESC")
    List<NewsData> findAllByImageUrlIsNullOrEmpty();

    // test용도
    List<NewsData> findTop40ByOrderByPubDateDesc();

    // test용도
    @Query(value = "SELECT * FROM news ORDER BY pub_date DESC LIMIT 15 OFFSET 20", nativeQuery = true)
    List<NewsData> findNews31To40();
}
