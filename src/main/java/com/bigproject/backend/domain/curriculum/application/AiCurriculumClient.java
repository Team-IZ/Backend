package com.bigproject.backend.domain.curriculum.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Component
public class AiCurriculumClient {

    private final RestClient restClient;
    private final String internalKey;

    public AiCurriculumClient(
            @Value("${ai.curriculum.base-url:http://localhost:8000/api/v0}") String baseUrl,
            @Value("${ai.curriculum.x-internal-key:}") String internalKey) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.internalKey = internalKey;
        System.out.println("### DEBUG baseUrl=[" + baseUrl + "] internalKey length=" + internalKey.length() + " value=[" + internalKey + "]");
    }

    public record CurriculumAccepted(String jobId, String status) {
    }

    public record TeachesResult(String canonicalName, String normalizedName,
                                String description, BigDecimal confidence) {
    }

    public record SectionResult(int moduleNo, String title, int pageStart, int pageEnd,
                                List<String> keywords, BigDecimal confidence,
                                List<TeachesResult> teaches) {
    }

    public record AnalysisResult(String status, List<SectionResult> sections) {
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

    /** AI 서버(FastAPI) GET /api/v0/curricula/{job_id} 호출. 분석 상태·결과 조회. */
    public AnalysisResult checkStatus(String jobId) {
        return restClient.get()
                .uri("/curricula/{jobId}", jobId)
                .header("X-Internal-Key", internalKey)
                .retrieve()
                .body(AnalysisResult.class);
    }
}