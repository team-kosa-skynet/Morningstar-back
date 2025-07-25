package com.gaebang.backend.domain.community.entity;

import com.gaebang.backend.domain.community.dto.reqeust.BoardCreateAndEditRequestDto;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;


@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Getter
@Entity
public class Board extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "board_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @OneToMany(mappedBy = "board")
    private List<BoardLike> boardLikes = new ArrayList<>();

    @OneToMany(mappedBy = "board")
    private List<Comment> comments = new ArrayList<>();

    @OneToMany(mappedBy = "board")
    private List<BoardReport> boardReports = new ArrayList<>();

    @OneToMany(mappedBy = "board")
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
}
