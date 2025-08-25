package com.gaebang.backend.domain.interview.entity;

import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "interviews_answer",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_answer_session_qidx",
                        columnNames = {"session_id", "question_index"}
                )
        }
)
@Entity
public class InterviewAnswer extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", referencedColumnName = "id")
    private InterviewSession session;

    private int questionIndex;
    private String questionType;      // BEHAVIORAL / SYSTEM_DESIGN / ...

    @Lob
    private String questionText;

    @Lob
    private String transcript;

    @Lob
    private String metricsJson;       // 간단 채점/코칭(JSON)

    @Column(name = "llm_response_id", length = 128)
    private String llmResponseId;

    @Column(name = "prev_response_id", length = 128)
    private String prevResponseId;

    public static InterviewAnswer create(InterviewSession session,
                                         int questionIndex,
                                         String questionType,
                                         String questionText,
                                         String transcript,
                                         String metricsJson,
                                         String llmResponseId,
                                         String prevResponseId) {   // ★ 추가
        InterviewAnswer answer = new InterviewAnswer();
        answer.session = session;
        answer.questionIndex = questionIndex;
        answer.questionType = questionType;
        answer.questionText = questionText;
        answer.transcript = transcript;
        answer.metricsJson = metricsJson;
        answer.llmResponseId = llmResponseId;
        answer.prevResponseId = prevResponseId;     // ★ 추가
        return answer;
    }
}
