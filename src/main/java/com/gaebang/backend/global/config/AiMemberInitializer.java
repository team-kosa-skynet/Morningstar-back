package com.gaebang.backend.global.config;

import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.domain.member.repository.MemberRepository;
import com.gaebang.backend.domain.pointTier.entity.PointTier;
import com.gaebang.backend.domain.pointTier.repository.PointTierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// AI 어시스턴트 Member 계정 자동 생성
@Slf4j
@Component
@RequiredArgsConstructor
public class AiMemberInitializer {

    private static final Long AI_BOT_MEMBER_ID = 999L;
    
    private final MemberRepository memberRepository;
    private final PointTierRepository pointTierRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initializeAiMember() {
        if (memberRepository.existsById(AI_BOT_MEMBER_ID)) {
            log.info("[AiMemberInitializer] AI 어시스턴트 계정이 이미 존재합니다 (ID: {})", AI_BOT_MEMBER_ID);
            return;
        }

        try {
            PointTier defaultTier = pointTierRepository.findAll()
                    .stream()
                    .min((t1, t2) -> Integer.compare(t1.getTierOrder(), t2.getTierOrder()))
                    .orElse(null);

            if (defaultTier == null) {
                log.warn("[AiMemberInitializer] PointTier 테이블이 비어있습니다. AI Member 생성을 건너뜁니다.");
                return;
            }

            // AI Member 생성
            Member aiMember = Member.builder()
                    .email("ai-assistant@morningstar.com")
                    .nickname("AI어시스턴트")
                    .password("") // 빈 패스워드
                    .authority("ROLE_BOT")
                    .provider("SYSTEM")
                    .currentTier(defaultTier)
                    .build();

            memberRepository.save(aiMember);
            log.info("[AiMemberInitializer] AI 어시스턴트 계정이 생성되었습니다 (ID: {}, nickname: {})", 
                    aiMember.getId(), aiMember.getMemberBase().getNickname());

        } catch (Exception e) {
            log.error("[AiMemberInitializer] AI 어시스턴트 계정 생성 실패: {}", e.getMessage(), e);
        }
    }
}