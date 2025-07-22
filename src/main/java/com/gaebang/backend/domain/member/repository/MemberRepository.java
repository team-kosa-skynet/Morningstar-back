package com.gaebang.backend.domain.member.repository;

import com.gaebang.backend.domain.member.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository
        extends JpaRepository<Member, Long> {

    Optional<Member> findByMemberBaseEmail(String email);

    Optional<Member> findByMemberBaseEmailAndProvider(String email, String provider);

    Optional<Member> findByMemberBase_Nickname(String memberBaseNickname);
}
