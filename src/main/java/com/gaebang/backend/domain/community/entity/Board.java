package com.gaebang.backend.domain.community.entity;

import com.gaebang.backend.domain.community.dto.reqeust.BoardCreateAndEditRequestDto;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Getter
@Entity
public class Board extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "board_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @OneToMany(mappedBy = "board")
    @Builder.Default
    private List<BoardLike> boardLikes = new ArrayList<>();

    @OneToMany(mappedBy = "board")
    @Builder.Default
    private List<Comment> comments = new ArrayList<>();

    @OneToMany(mappedBy = "board")
    @Builder.Default
    private List<BoardReport> boardReports = new ArrayList<>();

    @OneToMany(mappedBy = "board")
    @Builder.Default
    private List<Image> images = new ArrayList<>();

    private String title;

    private String content;

    @Builder.Default
    @Column(nullable = false)
    private String deleteYn = "N";


    private String category;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "BIGINT DEFAULT 1")
    private Long viewCount = 0L;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false)
    private ModerationStatus moderationStatus = ModerationStatus.PENDING;

    private LocalDateTime moderatedAt;

    public void updateBoard(BoardCreateAndEditRequestDto dto) {
        this.title = dto.title();
        this.content = dto.content();
        this.category = dto.category();
    }

    public void plusviewCount() {
        this.viewCount++;
    }

    public void softDelete() {
        this.deleteYn = "Y";
    }

    public void censorContent(String censoredTitle, String censoredContent) {
        this.title = censoredTitle;
        this.content = censoredContent;
        this.moderationStatus = ModerationStatus.REJECTED;
        this.moderatedAt = LocalDateTime.now();
    }

    public void approveModerationContent() {
        this.moderationStatus = ModerationStatus.APPROVED;
        this.moderatedAt = LocalDateTime.now();
    }
}
