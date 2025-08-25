package com.gaebang.backend.domain.interview.entity;

import com.gaebang.backend.domain.interview.enums.InterviewMode;
import com.gaebang.backend.domain.interview.enums.InterviewStatus;
import com.gaebang.backend.domain.member.entity.Member;
import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "interviews_session")
@Entity
public class InterviewSession extends BaseTimeEntity {

    @Id
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;                   // 애플리케이션에서 UUID 할당

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    private String displayName;
    private String role;              // BACKEND / FRONTEND / UNKNOWN

    @Enumerated(EnumType.STRING)
    private InterviewMode mode;

    @Enumerated(EnumType.STRING)
    private InterviewStatus status;

    private int questionIndex;        // 진행 포인터(0-base)

    @Lob
    private String planJson;          // 인터뷰 플랜(질문 10개 등)

    @Column(name = "last_response_id", length = 128)
    private String lastResponseId;

    @Lob
    private String profileSnapshotJson; // PDF 요약/구조화

    private OffsetDateTime finishedAt;

    public static InterviewSession create(UUID id,
                                          Member member,
                                          String displayName,
                                          String role,
                                          InterviewMode mode,
                                          String profileSnapshotJson,
                                          String planJson) {
        InterviewSession session = new InterviewSession();
        session.id = id;
        session.member = member;
        session.displayName = displayName;
        session.role = role;
        session.mode = mode;
        session.status = InterviewStatus.READY;
        session.questionIndex = 0;
        session.profileSnapshotJson = profileSnapshotJson;
        session.planJson = planJson;
        return session;
    }

    public void advance() {
        this.questionIndex += 1;
        this.status = InterviewStatus.RUNNING;
    }

    public void finishNow(OffsetDateTime finishedAt) {
        this.status = InterviewStatus.FINISHED;
        this.finishedAt = finishedAt;
    }

    public void updateLastResponseId(String lastResponseId) {
        this.lastResponseId = lastResponseId;
    }

    public void updateProfileSnapshotJson(String json) {
        this.profileSnapshotJson = json;
    }
}
