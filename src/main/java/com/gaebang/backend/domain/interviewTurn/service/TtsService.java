package com.gaebang.backend.domain.interviewTurn.service;

import com.gaebang.backend.domain.interviewTurn.dto.request.TtsRequestDto;
import com.gaebang.backend.domain.interviewTurn.llm.TtsGateway;
import org.springframework.stereotype.Service;

@Service
public class TtsService {

    private final TtsGateway ttsGateway;

    public TtsService(TtsGateway ttsGateway) {
        this.ttsGateway = ttsGateway;
    }

    public byte[] speak(TtsRequestDto req) throws Exception {
        return ttsGateway.synthesize(req.text().trim(), req.voice(), req.format());
    }
}
