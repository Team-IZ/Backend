package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumErrorCode;
import com.bigproject.backend.domain.curriculum.domain.CurriculumException;
import com.bigproject.backend.global.ai.AiClient;
import com.bigproject.backend.global.ai.AiProxyWarmUp;
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

    /**
     * base-url 에는 호스트만 있다. 프리픽스는 {@link AiClient#API_V0} 한 곳에서만 붙인다 —
     * base-url 쪽에도 붙이면 {@code RestClient} 가 두 경로를 <b>이어붙여</b>
     * {@code /api/v0/api/v0/curricula} 가 된다(치환이 아니다).
     */
    private static final String CURRICULA_PATH = AiClient.API_V0 + "/curricula";

    private final AiProxyWarmUp proxyWarmUp;
    private final RestClient originClient;
    private final String internalKey;

    /**
     * PDF 업로드는 프록시가 아니라 원본({@code ai.origin-base-url})으로 직접 나간다.
     *
     * <p><b>왜 프록시로 보내지 않나.</b> 프록시는 wake/sleep Lambda이고, Lambda Function URL의
     * 동기 호출 페이로드 상한이 6MB다. PDF 자체를 그 경로로 보내면 413이 난다(Team-IZ/AI PR#25
     * 코멘트에서 10MB로 재현·확인. AWS 플랫폼 제약이라 프록시 코드로 우회할 수 없다).
     *
     * <p><b>왜 그런데도 프록시를 먼저 부르나.</b> 원본 도메인은 PAUSED 상태를 스스로 깨우지 못하고
     * 즉시 404만 돌려준다(Team-IZ-AI HANDOFF). 깨우는 일은 {@link AiProxyWarmUp} 이 맡는다 —
     * 코드 제출 경로와 같은 컴포넌트다. 두 벌로 두면 타임아웃 정책이 갈라진다.
     */
    public AiCurriculumClient(
            AiProxyWarmUp proxyWarmUp,
            @Value("${ai.origin-base-url:http://localhost:8000}") String originBaseUrl,
            @Value("${ai.curriculum.x-internal-key:}") String internalKey) {
        this.proxyWarmUp = proxyWarmUp;

        SimpleClientHttpRequestFactory originFactory = new SimpleClientHttpRequestFactory();
        originFactory.setConnectTimeout(Duration.ofSeconds(5));
        originFactory.setReadTimeout(Duration.ofSeconds(60));
        this.originClient = RestClient.builder().baseUrl(originBaseUrl).requestFactory(originFactory).build();

        this.internalKey = internalKey;
    }

    public record CurriculumAccepted(String jobId, String status) {
    }

    public record TeachesResult(String canonicalName, String normalizedName,
                                String canonicalDescription, BigDecimal confidence,
                                Integer descriptionPageStart, Integer descriptionPageEnd,
                                String kind, String evidence, List<String> siblingNames) {
        public String description() {
            return canonicalDescription;
        }
    }

    public record SectionResult(int moduleNo, String title, int pageStart, int pageEnd,
                                List<String> keywords, BigDecimal confidence,
                                List<TeachesResult> teaches) {
    }

    public record CurriculumResultPayload(String versionId, Integer analysisVersion,
                                          String heuristicVersion, String promptVersion,
                                          String extractionStatus, String qualityStatus,
                                          Boolean fallbackUsed, List<SectionResult> sections) {
    }

    public record AnalysisResult(String jobId, String versionId, String status,
                                 String failureReason, String startedAt, String completedAt,
                                 CurriculumResultPayload result) {
        public List<SectionResult> sections() {
            return result != null ? result.sections() : null;
        }
    }

    public CurriculumAccepted requestAnalysis(UUID versionId, String courseLabel, byte[] pdfBytes, String idempotencyKey) {
        if (!proxyWarmUp.warmUp()) {
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_AI_UNAVAILABLE);
        }

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
                .uri(CURRICULA_PATH)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Internal-Key", internalKey)
                .body(body)
                .retrieve()
                .body(CurriculumAccepted.class);
    }

    public AnalysisResult checkStatus(String jobId) {
        return originClient.get()
                .uri(CURRICULA_PATH + "/{jobId}", jobId)
                .header("X-Internal-Key", internalKey)
                .retrieve()
                .body(AnalysisResult.class);
    }
}