package com.gaebang.backend.domain.interview.entity;

import com.gaebang.backend.domain.interview.enums.InterviewStage;
import com.gaebang.backend.domain.interview.enums.InterviewStatus;
import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "interview_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterviewSession extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 36)
    private UUID id;

    private Long userId;
    @Column(length = 64)
    private String jobPosition;

    @Column(length = 80, nullable = false)
    private String model; // ex) gpt-4o-mini / gpt-4o-realtime-...

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private InterviewStatus status = InterviewStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(length = 24, nullable = false)
    private InterviewStage stage = InterviewStage.ICEBREAK;

    // 턴 기반(Responses API)에서만 사용. Realtime 모드면 null 가능.
    @Column(length = 128)
    private String lastResponseId;

    @Column(nullable = false)
    private int questionNo;

    @Column(nullable = false)
    private int maxQuestions = 10;

    @Version
    private Long version;

    private LocalDateTime endedAt;

    // ----- 팩토리/행위 -----
    public static InterviewSession startTurnMode(String model, String jobPosition, Long userId,
                                                 String firstResponseId, int maxQuestions) {
        InterviewSession session = new InterviewSession(); // var 대신 명시 타입도 OK
        session.model = model;
        session.jobPosition = jobPosition;
        session.userId = userId;
        session.lastResponseId = firstResponseId;
        session.questionNo = 1;
        session.maxQuestions = maxQuestions;
        session.status = InterviewStatus.ACTIVE;
        return session;
    }

    public static InterviewSession startRealtime(String model, String jobPosition, Long userId) {
        InterviewSession session = new InterviewSession();
        session.model = model;
        session.jobPosition = jobPosition;
        session.userId = userId;
        session.questionNo = 1;
        session.status = InterviewStatus.ACTIVE;
        return session;
    }

    /** 턴 기반: Responses API 새 id 반영 */
    public void advanceWithResponse(String newResponseId) {
        this.lastResponseId = newResponseId;
        this.questionNo += 1;
    }

    /** Realtime 모드: 응답 id 없이 턴만 증가 */
    public void advanceRealtime() {
        this.questionNo += 1;
    }

    public boolean isFinished() {
        return this.status != InterviewStatus.ACTIVE || this.questionNo >= this.maxQuestions;
    }

    public void finish() {
        this.status = InterviewStatus.FINISHED;
        this.endedAt = LocalDateTime.now();
    }

    public void cancel() {
        this.status = InterviewStatus.CANCELLED;
        this.endedAt = LocalDateTime.now();
    }

    public void moveToStage(InterviewStage stage) {
        this.stage = stage;
    }
}