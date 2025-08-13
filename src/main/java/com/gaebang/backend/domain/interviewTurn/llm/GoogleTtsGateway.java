package com.gaebang.backend.domain.interviewTurn.llm;

import com.google.cloud.texttospeech.v1.*;
import org.springframework.stereotype.Service;

@Service
public class GoogleTtsGateway implements TtsGateway  {

    @Override
    public byte[] synthesize(String text, String voice, String format) throws Exception {
        try (TextToSpeechClient tts = TextToSpeechClient.create()) {
            // 1) 입력 텍스트
            SynthesisInput input = SynthesisInput.newBuilder()
                    .setText(text)
                    .build();

            // 2) 보이스 (없으면 기본값)
            VoiceSelectionParams.Builder vb = VoiceSelectionParams.newBuilder()
                    .setLanguageCode("ko-KR"); // 기본 한국어
            if (voice != null && !voice.isBlank()) {
                vb.setName(voice); // 예: "ko-KR-Standard-A"
            }
            VoiceSelectionParams voiceSel = vb.build();

            // 3) 포맷 (기본 MP3)
            AudioEncoding encoding;
            String fmt = (format == null ? "" : format).toLowerCase();
            switch (fmt) {
                case "wav":
                case "linear16":
                    encoding = AudioEncoding.LINEAR16; // (컨테이너 없는 raw PCM일 수 있음)
                    break;
                case "ogg":
                case "ogg_opus":
                    encoding = AudioEncoding.OGG_OPUS;
                    break;
                default:
                    encoding = AudioEncoding.MP3;
            }
            AudioConfig audio = AudioConfig.newBuilder()
                    .setAudioEncoding(encoding)
                    .build();

            // 4) 합성
            SynthesizeSpeechResponse resp = tts.synthesizeSpeech(input, voiceSel, audio);
            return resp.getAudioContent().toByteArray();
        }
    }
}
