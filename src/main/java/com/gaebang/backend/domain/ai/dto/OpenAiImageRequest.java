package com.gaebang.backend.domain.ai.dto;

public class OpenAiImageRequest {
    public String model;
    public String prompt;
    public int n;
    public String size;
    public String quality;

    public OpenAiImageRequest(String model, String prompt, int n, String size, String quality) {
        this.model = model;
        this.prompt = prompt;
        this.n = n;
        this.size = size;
        this.quality = quality;
    }
}