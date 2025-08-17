package com.gaebang.backend.domain.interview.service;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SpringAiInterviewerService implements InterviewerAiService {

    private final ChatClient chatClient;

    private static final String SYSTEM = """
        당신은 전문적인 한국어 면접관입니다.
        - 친근하면서도 전문적인 톤
        - 지원자 답변을 바탕으로 후속 질문
        - 질문은 간결/명확, 한 번에 하나
        - 사실에 없는 내용은 만들지 않기
        """;

    @Override
    public String generateFirstQuestion(String jobPosition) {
        return chatClient.prompt()
                .system(SYSTEM)
                .user(jobPosition + " 포지션 면접을 시작합니다. 첫 질문 1개만 생성하세요.")
                .call()
                .content();
    }

    @Override
    public String generateNextQuestion(String situation, String candidateAnswer) {
        String user = "면접 상황: " + situation + "\n지원자 답변: " + candidateAnswer + "\n다음 질문 1개만 생성하세요.";
        return chatClient.prompt()
                .system(SYSTEM)
                .user(user)
                .call()
                .content();
    }

}
