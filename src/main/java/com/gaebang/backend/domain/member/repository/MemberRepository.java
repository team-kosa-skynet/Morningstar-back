package com.gaebang.backend.domain.member.repository;

import com.gaebang.backend.domain.member.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MemberRepository
        extends JpaRepository<Member, Long> {

    Optional<Member> findByMemberBaseEmail(String email);

    Optional<Member> findByMemberBaseEmailAndProvider(String email, String provider);

    Optional<Member> findByMemberBase_Nickname(String memberBaseNickname);
    
    /**
     * Member의 현재 포인트를 원자적으로 업데이트
     * 데이터 정합성 보장을 위한 단일 쿼리 실행
     */
    @Modifying
    @Query("UPDATE Member m SET m.points = :point WHERE m.id = :memberId")
    void updateCurrentPoint(@Param("memberId") Long memberId, @Param("point") Integer point);
}
