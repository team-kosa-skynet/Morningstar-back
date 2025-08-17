package com.gaebang.backend.domain.interview.entity;

import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "uploaded_document",
        indexes = {
                @Index(name = "idx_document_member", columnList = "member_id")
        }
)
@Entity
public class UploadedDocument extends BaseTimeEntity {

    @Id
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    private String fileName;
    private String fileType;
    private Long fileSize;

    @Lob
    private String extractedContent;

    public static UploadedDocument create(UUID id,
                                          Member member,
                                          String fileName,
                                          String fileType,
                                          Long fileSize,
                                          String extractedContent) {
        UploadedDocument document = new UploadedDocument();
        document.id = id;
        document.member = member;
        document.fileName = fileName;
        document.fileType = fileType;
        document.fileSize = fileSize;
        document.extractedContent = extractedContent;
        return document;
    }
}