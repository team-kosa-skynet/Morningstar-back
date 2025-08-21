package com.gaebang.backend.domain.community.entity;

import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Getter
@Builder
@Entity
public class Comment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "comment_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "board_id")
    private Board board;

    @OneToMany(mappedBy = "comment")
    @Builder.Default
    private List<CommentReport> commentReport = new ArrayList<>();

    private String content;

    @Builder.Default
    @Column(nullable = false)
    private String deleteYn = "N";

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false)
    private ModerationStatus moderationStatus = ModerationStatus.PENDING;

    private LocalDateTime moderatedAt;

    public void update(String content) {
        this.content = content;
    }

    public void softDelete() {
        this.deleteYn = "Y";
    }

    public void censorContent(String censoredContent) {
        this.content = censoredContent;
        this.moderationStatus = ModerationStatus.REJECTED;
        this.moderatedAt = LocalDateTime.now();
    }

    public void approveModerationContent() {
        this.moderationStatus = ModerationStatus.APPROVED;
        this.moderatedAt = LocalDateTime.now();
    }
}
