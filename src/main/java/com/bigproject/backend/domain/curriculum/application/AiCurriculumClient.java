package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumErrorCode;
import com.bigproject.backend.domain.curriculum.domain.CurriculumException;
import com.bigproject.backend.global.ai.AiCallException;
import com.bigproject.backend.global.ai.AiClient;
import com.bigproject.backend.global.ai.AiClientConfig;
import com.bigproject.backend.global.ai.AiProxyWarmUp;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
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
    private final AiClient aiOriginClient;

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
     *
     * <p>🔴 <b>2026-08-20 — 자체 {@code RestClient} 대신 공용 {@link AiClient}를 쓴다.</b>
     * {@code ai.origin-base-url}을 향하는 {@code AiClientConfig#aiOriginClient}가 정확히
     * 교안·코드 제출 분석용으로 이미 있었는데({@code AiClientConfig} 클래스 주석에 명시), 이 클래스는
     * 그걸 안 쓰고 자기 {@code RestClient}를 따로 두고 있었다. 그래서 연결 실패·타임아웃·AI의
     * 4xx·5xx 응답이 전부 무방비로 새 나가 코드 없는 500이 됐다(2026-08-20 재현: POST
     * /curricula/{materialId}/analyses). 공용 클라이언트는 그 셋을 전부 {@link AiCallException}
     * 하나로 접어 준다 — 아래에서 그걸 받아 도메인 예외로 바꾼다.
     *
     * <p>내부 인증 헤더도 정리한다. 종전엔 {@code ai.curriculum.x-internal-key}를 따로 받았는데,
     * 그 값은 {@code ai.internal-key}와 같은 {@code AI_INTERNAL_KEY} 환경변수를 가리키는 중복이었다
     * (application.yaml 주석 "정리 후보"). 공용 클라이언트가 이미 그 헤더를 붙이므로 이 클래스는
     * 더 이상 내부 키를 직접 다루지 않는다.
     */
    public AiCurriculumClient(
            AiProxyWarmUp proxyWarmUp,
            @Qualifier(AiClientConfig.AI_ORIGIN_CLIENT) AiClient aiOriginClient) {
        this.proxyWarmUp = proxyWarmUp;
        this.aiOriginClient = aiOriginClient;
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

    /**
     * @throws CurriculumException {@code CURRICULUM_AI_UNAVAILABLE} — 웜업 실패, 또는
     *                              {@link AiCallException}으로 잡힌 연결 실패·타임아웃·AI 오류 응답
     */
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

        try {
            return aiOriginClient.postMultipart(
                    CURRICULA_PATH, body, CurriculumAccepted.class, idempotencyKey, null);
        } catch (AiCallException exception) {
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_AI_UNAVAILABLE);
        }
    }

    /** @throws CurriculumException {@code CURRICULUM_AI_UNAVAILABLE} — {@link AiCallException}으로 잡힌 실패 전부 */
    public AnalysisResult checkStatus(String jobId) {
        try {
            return aiOriginClient.get(CURRICULA_PATH + "/" + jobId, AnalysisResult.class, null);
        } catch (AiCallException exception) {
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_AI_UNAVAILABLE);
        }
    }
}
