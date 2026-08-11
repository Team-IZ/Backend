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
                                String description, BigDecimal confidence) {
    }

    public record SectionResult(int moduleNo, String title, int pageStart, int pageEnd,
                                List<String> keywords, BigDecimal confidence,
                                List<TeachesResult> teaches) {
    }

    public record AnalysisResult(String status, List<SectionResult> sections) {
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
