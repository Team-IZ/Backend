package com.bigproject.backend.domain.curriculum.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

@Component
public class AiCurriculumClient {

    private final RestClient proxyClient;
    private final RestClient originClient;
    private final String internalKey;

    /**
     * PDF 업로드는 두 클라이언트로 나뉜다.
     *
     * proxyClient — wake/sleep Lambda 프록시. 페이로드 없는 헬스체크로 PAUSED 상태를 깨우는
     * 용도로만 쓴다. Lambda Function URL의 동기 호출 페이로드 상한이 6MB라 PDF 자체를 이
     * 경로로 보내면 413이 난다(Team-IZ/AI PR#25 코멘트에서 10MB로 재현·확인됨, AWS 플랫폼
     * 제약이라 프록시 코드로 우회 불가).
     *
     * originClient — App Runner 원본 도메인. 실제 PDF 멀티파트는 여기로 직접 보내 6MB 상한을
     * 피한다. 단, 원본 도메인은 PAUSED 상태를 스스로 깨우지 못하고 즉시 404만 반환하므로
     * (Team-IZ-AI HANDOFF 참조) proxyClient로 먼저 깨운 뒤에만 써야 한다.
     */
    public AiCurriculumClient(
            @Value("${ai.curriculum.base-url:http://localhost:8000}") String proxyBaseUrl,
            @Value("${ai.curriculum.origin-base-url:${ai.curriculum.base-url:http://localhost:8000}}") String originBaseUrl,
            @Value("${ai.curriculum.x-internal-key:}") String internalKey) {
        SimpleClientHttpRequestFactory proxyFactory = new SimpleClientHttpRequestFactory();
        proxyFactory.setConnectTimeout(Duration.ofSeconds(5));
        // 유휴 후 첫 웜업은 프록시가 ResumeService+RUNNING 대기를 동기로 하므로 최대 ~80초 관측됨.
        proxyFactory.setReadTimeout(Duration.ofSeconds(150));
        this.proxyClient = RestClient.builder().baseUrl(proxyBaseUrl).requestFactory(proxyFactory).build();

        SimpleClientHttpRequestFactory originFactory = new SimpleClientHttpRequestFactory();
        originFactory.setConnectTimeout(Duration.ofSeconds(5));
        originFactory.setReadTimeout(Duration.ofSeconds(60));
        this.originClient = RestClient.builder().baseUrl(originBaseUrl).requestFactory(originFactory).build();

        this.internalKey = internalKey;
    }

    public record CurriculumAccepted(String jobId, String status) {
    }

    /** AI 서버(FastAPI) POST /api/v0/curricula 호출. PDF를 다시 전송한다. */
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
                .uri("/api/v0/curricula")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Internal-Key", internalKey)
                .body(body)
                .retrieve()
                .body(CurriculumAccepted.class);
    }

    /** /api/health는 X-Internal-Key 인증이 면제된다(AI README 참조) — 헤더 없이 깨우기만 한다. */
    private void warmUp() {
        proxyClient.get()
                .uri("/api/health")
                .retrieve()
                .toBodilessEntity();
    }
}