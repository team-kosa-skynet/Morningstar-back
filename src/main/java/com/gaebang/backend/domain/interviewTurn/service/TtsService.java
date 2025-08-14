package com.gaebang.backend.domain.interviewTurn.service;

import com.gaebang.backend.domain.interviewTurn.dto.response.TtsPayloadDto;
import org.springframework.stereotype.Service;

@Service
public interface TtsService {

    TtsPayloadDto synthesize(String text, String format) throws Exception;
}
