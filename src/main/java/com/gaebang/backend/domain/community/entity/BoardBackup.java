package com.gaebang.backend.domain.community.entity;

import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Getter
@Builder
@Entity
public class BoardBackup extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "board_backup_id")
    private Long id;

    @Column(nullable = false)
    private Long boardId;

    @Column(columnDefinition = "TEXT")
    private String originalTitle;

    @Column(columnDefinition = "TEXT")
    private String originalContent;

    private String censorReason;

    public static BoardBackup createBackup(Long boardId, String originalTitle, String originalContent, String censorReason) {
        return BoardBackup.builder()
                .boardId(boardId)
                .originalTitle(originalTitle)
                .originalContent(originalContent)
                .censorReason(censorReason)
                .build();
    }
}