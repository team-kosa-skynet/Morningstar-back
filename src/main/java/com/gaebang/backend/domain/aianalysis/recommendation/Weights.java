package com.gaebang.backend.domain.aianalysis.recommendation;

import lombok.Data;

@Data
public class Weights {
    public double cost;
    public double speed;
    public double math;
    public double code;
    public double knowledge;
    public double reasoning;
    public double recency;
}
