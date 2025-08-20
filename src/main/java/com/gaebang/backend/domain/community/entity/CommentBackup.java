package com.gaebang.backend.domain.community.entity;

import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Getter
@Builder
@Entity
public class CommentBackup extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "comment_backup_id")
    private Long id;

    @Column(nullable = false)
    private Long commentId;

    @Column(columnDefinition = "TEXT")
    private String originalContent;

    private String censorReason;

    public static CommentBackup createBackup(Long commentId, String originalContent, String censorReason) {
        return CommentBackup.builder()
                .commentId(commentId)
                .originalContent(originalContent)
                .censorReason(censorReason)
                .build();
    }
}