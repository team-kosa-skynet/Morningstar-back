package com.gaebang.backend.domain.interview.entity;

import com.gaebang.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "candidate_profile")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CandidateProfile extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 세션별 1:1로 보관
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", unique = true)
    private InterviewSession session;

    /** 자유 형식 JSON (예: {"years":3, "skills":["Java","Spring"], "projects":[...]} ) */
    @Lob
    @Column(nullable = false)
    private String snapshotJson;

    public CandidateProfile(InterviewSession session, String snapshotJson) {
        this.session = session;
        this.snapshotJson = snapshotJson;
    }

    public void updateJson(String snapshotJson) {
        this.snapshotJson = snapshotJson;
    }
}