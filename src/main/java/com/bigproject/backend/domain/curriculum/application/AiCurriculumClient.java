package com.bigproject.backend.domain.curriculum.application;

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

    private final RestClient restClient;
    private final String internalKey;

    /**
     * base-url 에 이미 {@code /api/v0} 가 붙어 있다(application.yaml). 종전에는 프리픽스가 설정에 없고
     * 경로 쪽에 적혀 있었는데, 그러면 공통 {@code AI_BASE_URL} 을 쓸 수 없다 — 그 값에는 프리픽스가
     * 포함돼 있어 {@code /api/v0/api/v0/curricula} 가 된다.
     */
    public AiCurriculumClient(
            @Value("${ai.curriculum.base-url:http://localhost:8000/api/v0}") String baseUrl,
            @Value("${ai.curriculum.x-internal-key:}") String internalKey) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.internalKey = internalKey;
    }

    public record CurriculumAccepted(String jobId, String status) {
    }

    /** AI 서버(FastAPI) POST /api/v0/curricula 호출. PDF를 다시 전송한다. */
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
                .uri("/curricula")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Internal-Key", internalKey)
                .body(body)
                .retrieve()
                .body(CurriculumAccepted.class);
    }
}