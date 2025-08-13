package com.gaebang.backend.domain.interviewTurn.llm;

public interface TtsGateway {
    byte[] synthesize(String text, String voice, String format) throws Exception;
}
