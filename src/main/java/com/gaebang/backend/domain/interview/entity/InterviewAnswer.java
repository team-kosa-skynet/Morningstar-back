package com.gaebang.backend.domain.interview.entity;

import com.gaebang.backend.domain.interview.enums.InterviewStage;
import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "interview_answer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterviewAnswer extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // FK
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private InterviewSession session;

    @Column(nullable = false)
    private int questionNo;

    @Enumerated(EnumType.STRING)
    @Column(length = 24, nullable = false)
    private InterviewStage stage;

    @Lob
    @Column(nullable = false)
    private String userTranscript;

    @Column
    private Integer durationMs;

    private InterviewAnswer(InterviewSession session, int questionNo, InterviewStage stage,
                            String userTranscript, Integer durationMs) {
        this.session = session;
        this.questionNo = questionNo;
        this.stage = stage;
        this.userTranscript = userTranscript;
        this.durationMs = durationMs;
    }

    public static InterviewAnswer of(InterviewSession session, int questionNo, InterviewStage stage,
                                     String userTranscript, Integer durationMs) {
        return new InterviewAnswer(session, questionNo, stage, userTranscript, durationMs);
    }
}
