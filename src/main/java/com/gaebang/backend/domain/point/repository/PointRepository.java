package com.gaebang.backend.domain.point.repository;

import com.gaebang.backend.domain.point.entity.Point;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PointRepository extends JpaRepository<Point, Long> {

    List<Point> findPointsByMemberIdOrderByVersionDesc(Long memberId);

    @Query("SELECT p FROM Point p WHERE p.member.id = :memberId ORDER BY p.version DESC LIMIT 1")
    Optional<Point> findLatestPointByMemberId(@Param("memberId") Long memberId);

//    @Query("SELECT MAX(p.version) FROM Point p WHERE p.member.id = :memberId")
//    Integer findMaxVersionByMemberId(@Param("memberId") Long memberId);

}