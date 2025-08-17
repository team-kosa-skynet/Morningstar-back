package com.gaebang.backend.domain.interview.service;

public interface InterviewerAiService {

    String generateNextQuestion(String situation, String candidateAnswer);
    String generateFirstQuestion(String jobPosition);

}
