package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.global.ai.AiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
public class AiCurriculumClient {

    private static final String CURRICULA_PATH = AiClient.API_V0 + "/curricula";

    private final RestClient restClient;
    private final String internalKey;

    /**
     * 교안 제출은 프록시를 거치지 않고 원본 서버({@code ai.origin-base-url})로 나간다(2026-08-11).
     *
     * <p>그 값에는 {@code /api/v0} 가 없다 — 대상 서버가 프록시·원본 둘로 갈라지면서 프리픽스를
     * 환경변수마다 붙이는 규약을 버렸다. 프리픽스는 {@link AiClient#API_V0} 로 경로 쪽에 있다.
     */
    public AiCurriculumClient(
            @Value("${ai.origin-base-url:http://localhost:8000}") String baseUrl,
            @Value("${ai.curriculum.x-internal-key:}") String internalKey) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.internalKey = internalKey;
    }

    public record CurriculumAccepted(String jobId, String status) {
    }

    /** AI 원본 서버(FastAPI) POST /api/v0/curricula 호출. PDF를 다시 전송한다. */
    public CurriculumAccepted requestAnalysis(UUID versionId, String courseLabel, byte[] pdfBytes, String idempotencyKey) {
        String payloadJson = "{\"versionId\":\"" + versionId + "\",\"courseLabel\":\"" + courseLabel + "\"}";

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("payload", payloadJson);
        body.add("file", new ByteArrayResource(pdfBytes) {
            @Override
            public String getFilename() {
                return "curriculum.pdf";
            }
        });

        return restClient.post()
                .uri(CURRICULA_PATH)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Internal-Key", internalKey)
                .body(body)
                .retrieve()
                .body(CurriculumAccepted.class);
    }
}