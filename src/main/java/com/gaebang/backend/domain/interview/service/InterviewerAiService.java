package com.gaebang.backend.domain.interview.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface InterviewerAiService {

    @SystemMessage("""
        당신은 전문적인 면접관입니다. 
        - 친근하면서도 전문적인 톤으로 대화하세요
        - 지원자의 답변을 바탕으로 적절한 후속 질문을 생성하세요
        - 질문은 간결하고 명확하게 만드세요
        - 한 번에 하나의 질문만 하세요
        """)
    @UserMessage("면접 상황: {{situation}}, 지원자 답변: {{answer}}. 다음 질문을 생성해주세요.")
    String generateNextQuestion(@V("situation") String situation, @V("answer") String candidateAnswer);

    @SystemMessage("당신은 친근한 면접관입니다. 면접을 시작하는 첫 질문을 생성해주세요.")
    @UserMessage("{{jobPosition}} 포지션 면접을 시작합니다.")
    String generateFirstQuestion(@V("jobPosition") String jobPosition);

}
