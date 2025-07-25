package com.gaebang.backend.domain.member.entity;

import com.gaebang.backend.domain.pointTier.entity.PointTier;
import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
public class Member extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long id;

    @Embedded
    protected MemberBase memberBase;

    private String provider;

    private int points;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tier_id")
    private PointTier currentTier;

    @Builder
    private Member(String email, String nickname, String password, String authority,
                   String provider) {
        this.memberBase = new MemberBase(email, nickname, password, authority);
        this.provider = provider;
        this.points = 0;
    }

    public void changePoint(int points){
        this.points = points;
    }

    public void changeTier(PointTier currentTier) {
        this.currentTier = currentTier;
    }
}
