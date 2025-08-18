package com.gaebang.backend.domain.interview.service;

import com.gaebang.backend.domain.interview.dto.response.TtsPayloadDto;
import org.springframework.stereotype.Service;

@Service
public interface TtsService {

    TtsPayloadDto synthesize(String text, String format) throws Exception;
}
