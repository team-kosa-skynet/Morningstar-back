package com.gaebang.backend.domain.aianalysis.recommendation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Model {
    private String name;
    private String creator;
    private boolean openSource;
    private String releaseDate;
    private Scores scores = new Scores();

    public Model(String name, String creator, boolean openSource, String releaseDate,
                 double cost, double speed, double math, double code,
                 double knowledge, double reasoning, double recency) {
        this.name = name;
        this.creator = creator;
        this.openSource = openSource;
        this.releaseDate = releaseDate;
        this.scores.cost = cost;
        this.scores.speed = speed;
        this.scores.math = math;
        this.scores.code = code;
        this.scores.knowledge = knowledge;
        this.scores.reasoning = reasoning;
        this.scores.recency = recency;
    }

    @Data
    public static class Scores {
        public double cost;       // 비용 점수
        public double speed;      // 속도 점수
        public double math;       // 수학 점수
        public double code;       // 코딩 점수
        public double knowledge;  // 지식 점수
        public double reasoning;  // 추론 점수
        public double recency;    // 최신성 점수
        public double finalScore; // 최종 점수

        public void calculateFinalScore(Weights w) {
            finalScore = cost * w.cost
                    + speed * w.speed
                    + math * w.math
                    + code * w.code
                    + knowledge * w.knowledge
                    + reasoning * w.reasoning
                    + recency * w.recency;
        }
    }
}
