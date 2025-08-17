package com.gaebang.backend.domain.ai.recommendation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Answers {
    private Purpose purpose;
    private AccuracyVsSpeed accuracyVsSpeed;
    private boolean longReasoning;
    private Double monthlyTokens;
    private CostPriority costPriority;
    private boolean needLowLatency;
    private Boolean allowOpenSource;
    private CreativityVsFact creativityVsFact;
    private boolean recentnessMatters;
    private int topK;

    public enum Purpose { CODING, MATH, KNOWLEDGE }
    public enum AccuracyVsSpeed { ACCURACY, SPEED, BALANCED }
    public enum CostPriority { CHEAP, EXPENSIVE, BALANCED }
    public enum CreativityVsFact { FACTUAL, CREATIVE }
}
