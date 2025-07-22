package com.gaebang.backend.domain.point.repository;

import com.project.stock.investory.point.entity.Point;
import org.apache.ibatis.annotations.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PointRepository extends JpaRepository<Point, Long> {

//    List<Point> findPointsByUserId(Long userId);
//
//    @Query("SELECT p FROM Point p WHERE p.user.id = :userId ORDER BY p.version DESC LIMIT 1")
//    Optional<Point> findLatestPointByUserId(@Param("userId") Long userId);
//
//    @Query("SELECT MAX(p.version) FROM Point p WHERE p.user.id = :userId")
//    Integer findMaxVersionByUserId(@Param("userId") Long userId);

    List<Point> findPointsByUserId(Long userId);

    // ROWNUM 사용 (Oracle 12c 이전 버전 호환)
    @Query(value = "SELECT * FROM (SELECT p.* FROM Point p WHERE p.user.userId = :userId ORDER BY p.version DESC) WHERE ROWNUM = 1", nativeQuery = true)
    Optional<Point> findLatestPointByUserId(@Param("userId") Long userId);

    // MAX 쿼리는 그대로 유지 (Oracle에서 정상 작동)
    @Query("SELECT MAX(p.version) FROM Point p WHERE p.user.userId = :userId")
    Integer findMaxVersionByUserId(@Param("userId") Long userId);

}