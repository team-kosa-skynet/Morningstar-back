package com.gaebang.backend.domain.community.repository;

import com.gaebang.backend.domain.community.dto.response.BoardListResponseDto;
import com.gaebang.backend.domain.community.dto.response.BoardListProjectionDto;
import com.gaebang.backend.domain.community.entity.Board;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BoardRepository extends JpaRepository<Board, Long> {

    @Query(value = "SELECT new com.gaebang.backend.domain.community.dto.response.BoardListProjectionDto(" +
            "b.id, " +
            "b.title," +
            "COUNT(distinct c)," +
            "b.member.memberBase.nickname," +
            "(SELECT img.imageUrl FROM Image img WHERE img.board = b AND img.id = " +
            "(SELECT MIN(img2.id) FROM Image img2 WHERE img2.board = b))," +
            "b.createdAt," +
            "b.viewCount, " +
            "COUNT(distinct bl)) FROM Board b " +
            "LEFT JOIN b.comments c " +
            "LEFT JOIN b.boardLikes bl " +
            "WHERE b.deleteYn = 'N' AND (b.title LIKE CONCAT('%', :condition, '%') " +
            "OR b.member.memberBase.nickname LIKE CONCAT('%', :condition, '%') " +
            "OR b.content LIKE CONCAT('%', :condition, '%')) " +
            "GROUP BY b",
            countQuery = "SELECT COUNT(DISTINCT b) FROM Board b " +
                    "LEFT JOIN b.comments c " +
                    "WHERE b.deleteYn = 'N' " +
                    "AND (b.title LIKE CONCAT('%', :condition, '%') " +
                    "OR b.member.memberBase.nickname LIKE CONCAT('%', :condition, '%') " +
                    "OR b.content LIKE CONCAT('%', :condition, '%'))")
    Page<BoardListProjectionDto> findByCondition(@Param("condition") String condition, Pageable pageable);

    @Query(value = "SELECT new com.gaebang.backend.domain.community.dto.response.BoardListProjectionDto(" +
            "b.id, " +
            "b.title," +
            "COUNT(distinct c)," +
            "b.member.memberBase.nickname," +
            "(SELECT img.imageUrl FROM Image img WHERE img.board = b AND img.id = " +
            "(SELECT MIN(img2.id) FROM Image img2 WHERE img2.board = b))," +
            "b.createdAt," +
            "b.viewCount, " +
            "COUNT(distinct bl)) FROM Board b " +
            "LEFT JOIN b.comments c " +
            "LEFT JOIN b.boardLikes bl " +
            "WHERE b.deleteYn = 'N' AND b.member.memberBase.nickname like CONCAT('%', :writer, '%') " +
            "GROUP BY b",
            countQuery = "SELECT COUNT(DISTINCT b) FROM Board b " +
                    "WHERE b.deleteYn = 'N' AND b.member.memberBase.nickname LIKE CONCAT('%', :writer, '%')")
    Page<BoardListProjectionDto> findByWriter(@Param("writer") String writer, Pageable pageable);

    // 통합으로 정책 변경으로 일단 주석처리
    /*@Query(value = "SELECT new com.gaebang.backend.domain.community.dto.response.BoardResponseDto(" +
            "b.id, " +
            "b.title," +
            "COUNT(distinct c)," +
            "b.member.memberBase.nickname," +
            "b.createdAt," +
            "b.viewCount, " +
            "COUNT(distinct bl)) FROM Board b LEFT JOIN b.comments c LEFT JOIN b.boardLikes bl " +
            "where b.content like CONCAT('%', :content, '%') " +
            "group by b",
            countQuery = "SELECT COUNT(DISTINCT b) FROM Board b " +
                    "LEFT JOIN b.comments c WHERE b.title LIKE %:content%")
    Page<BoardResponseDto> findByContent(@Param("content") String content, Pageable pageable);*/

    @Query(value = "SELECT new com.gaebang.backend.domain.community.dto.response.BoardListProjectionDto(" +
            "b.id, " +
            "b.title," +
            "COUNT(distinct c)," +
            "b.member.memberBase.nickname," +
            "(SELECT img.imageUrl FROM Image img WHERE img.board = b AND img.id = " +
            "(SELECT MIN(img2.id) FROM Image img2 WHERE img2.board = b))," +
            "b.createdAt," +
            "b.viewCount, " +
            "COUNT(distinct bl)) FROM Board b " +
            "LEFT JOIN b.comments c " +
            "LEFT JOIN b.boardLikes bl " +
            "WHERE b.deleteYn = 'N' " +
            "GROUP BY b",
            countQuery = "SELECT COUNT(DISTINCT b) FROM Board b " +
                    "WHERE b.deleteYn = 'N'")
    Page<BoardListProjectionDto> findAllBoardDtos(Pageable pageable);

    @Query("SELECT b FROM Board b WHERE b.id = :id AND b.member.id = :memberId AND b.deleteYn = 'N'")
    Optional<Board> findByIdAndMemberId(@Param("id") Long id, @Param("memberId") Long memberId);

    @Query("SELECT b FROM Board b " +
            "LEFT JOIN FETCH b.images " +
            "LEFT JOIN FETCH b.member " +
            "WHERE b.deleteYn = 'N' AND b.id = :boardId AND b.member.id = :memberId")
    Optional<Board> findBoardDetailById(@Param("boardId") Long boardId);
}
