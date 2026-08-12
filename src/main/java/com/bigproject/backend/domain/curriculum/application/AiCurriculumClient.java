package com.bigproject.backend.domain.curriculum.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
public class AiCurriculumClient {

    private final RestClient proxyClient;
    private final RestClient originClient;
    private final String internalKey;

    public AiCurriculumClient(
            @Value("${ai.proxy-base-url:http://localhost:8000}") String proxyBaseUrl,
            @Value("${ai.origin-base-url:http://localhost:8000}") String originBaseUrl,
            @Value("${ai.curriculum.x-internal-key:}") String internalKey) {
        SimpleClientHttpRequestFactory proxyFactory = new SimpleClientHttpRequestFactory();
        proxyFactory.setConnectTimeout(Duration.ofSeconds(5));
        proxyFactory.setReadTimeout(Duration.ofSeconds(150));
        this.proxyClient = RestClient.builder().baseUrl(proxyBaseUrl).requestFactory(proxyFactory).build();

        SimpleClientHttpRequestFactory originFactory = new SimpleClientHttpRequestFactory();
        originFactory.setConnectTimeout(Duration.ofSeconds(5));
        originFactory.setReadTimeout(Duration.ofSeconds(60));
        this.originClient = RestClient.builder().baseUrl(originBaseUrl + "/api/v0").requestFactory(originFactory).build();

        this.internalKey = internalKey;
    }

    public record CurriculumAccepted(String jobId, String status) {
    }

    public record TeachesResult(String canonicalName, String normalizedName,
                                String canonicalDescription, BigDecimal confidence,
                                Integer descriptionPageStart, Integer descriptionPageEnd,
                                String kind, String evidence, List<String> siblingNames) {
        // 기존 코드가 참조하던 description() 접근자 이름 호환용
        public String description() {
            return canonicalDescription;
        }
    }

    public record SectionResult(int moduleNo, String title, int pageStart, int pageEnd,
                                List<String> keywords, BigDecimal confidence,
                                List<TeachesResult> teaches) {
    }

    /** AI 응답의 result 필드 안에 실제 분석 산출물(sections 등)이 담겨 온다. */
    public record CurriculumResultPayload(String versionId, Integer analysisVersion,
                                          String heuristicVersion, String promptVersion,
                                          String extractionStatus, String qualityStatus,
                                          Boolean fallbackUsed, List<SectionResult> sections) {
    }

    /**
     * AI 서버 GET /api/v0/curricula/{job_id} 응답 최상위 구조.
     * sections는 최상위가 아니라 result 안에 있다 — 2026-08-11 실측(엑셀로 받은 실제 응답)으로
     * 확정. 예전 record는 sections를 최상위에서 찾아 항상 null이 되었고, 그 결과가
     * "SUCCEEDED인데 섹션이 비어있다"는 INVALID_AI_RESPONSE로 잘못 보고되고 있었다.
     */
    public record AnalysisResult(String jobId, String versionId, String status,
                                 String failureReason, String startedAt, String completedAt,
                                 CurriculumResultPayload result) {
        // 기존 코드(reconcileOne 등)가 result.sections()로 바로 접근하던 부분과의 호환용
        public List<SectionResult> sections() {
            return result != null ? result.sections() : null;
        }
    }

    public CurriculumAccepted requestAnalysis(UUID versionId, String courseLabel, byte[] pdfBytes, String idempotencyKey) {
        warmUp();

        String payloadJson = "{\"versionId\":\"" + versionId + "\",\"courseLabel\":\"" + courseLabel + "\"}";

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("payload", payloadJson);
        body.add("file", new ByteArrayResource(pdfBytes) {
            @Override
            public String getFilename() {
                return "curriculum.pdf";
            }
        });

        return originClient.post()
                .uri("/curricula")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Internal-Key", internalKey)
                .body(body)
                .retrieve()
                .body(CurriculumAccepted.class);
    }

    public AnalysisResult checkStatus(String jobId) {
        return originClient.get()
                .uri("/curricula/{jobId}", jobId)
                .header("X-Internal-Key", internalKey)
                .retrieve()
                .body(AnalysisResult.class);
    }

    private void warmUp() {
        proxyClient.get()
                .uri("/api/health")
                .retrieve()
                .toBodilessEntity();
    }
}
