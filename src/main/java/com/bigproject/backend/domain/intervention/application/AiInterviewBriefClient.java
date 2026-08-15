package com.bigproject.backend.domain.intervention.application;

import com.bigproject.backend.domain.intervention.application.dto.InterviewBriefAiRequest;
import com.bigproject.backend.domain.intervention.application.dto.InterviewBriefAiResponse;
import com.bigproject.backend.global.ai.AiClient;
import com.bigproject.backend.global.ai.AiClientConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 면담 브리프 생성 AI 호출.
 *
 * <h2>어느 {@code AiClient} 빈인가</h2>
 *
 * <p>{@code AiClientConfig}가 프록시·원본 두 빈을 등록하므로 {@link Qualifier}가 <b>필수</b>다 —
 * 안 붙이면 같은 타입 빈이 둘이라 주입이 모호해 기동 자체가 실패한다. 원본으로 나가는 것은
 * 교안·코드 제출(대용량 업로드)뿐이고 <i>그 외 전부</i> 프록시다.
 *
 * <h2>멱등키가 이 경로만 필수다</h2>
 *
 * <p>{@code ai_usage.idempotency_key}가 전역 UNIQUE인데 브리프는 {@code briefId} 하나로
 * 요청을 구분할 수 없다 — 재생성하면 {@code contextId}(=briefId)가 같아 키가 충돌한다.
 * 그래서 <b>버전을 붙여</b> {@code {briefId}:{versionNo}}로 만든다. 재시도는 반대로
 * 같은 키를 다시 써야 AI가 재계산 없이 캐시된 결과를 돌려준다.
 *
 * <h2>타임아웃을 줄이지 않는다</h2>
 *
 * <p>AI 엔진이 LLM 호출에 {@code SESSION_TIMEOUT_S=20}초 × {@code MAX_ATTEMPTS=6}회를 쓰므로
 * 최악 120초다. 백엔드 {@code ai.read-timeout} 기본값 150초가 그보다 길어 순서가 맞다 —
 * 짧게 잡으면 AI는 정상 작업 중인데 백엔드만 먼저 끊고 재시도해 <b>같은 요청에 LLM 비용이
 * 두 번</b> 나간다.
 */
@Component
public class AiInterviewBriefClient {

	private static final String PATH = AiClient.API_V0 + "/interview-briefs";

	private final AiClient aiClient;

	public AiInterviewBriefClient(@Qualifier(AiClientConfig.AI_PROXY_CLIENT) AiClient aiClient) {
		this.aiClient = aiClient;
	}

	/**
	 * 여는 말 + 질문 체크리스트를 생성한다. <b>블로킹 호출</b>이다 — 리턴값이 곧 결과다.
	 *
	 * @throws com.bigproject.backend.global.ai.AiCallException 4xx·5xx·타임아웃 전부.
	 *         {@code retryable}로 재시도 가치를 판단한다
	 */
	public InterviewBriefAiResponse generate(InterviewBriefAiRequest request, UUID briefId, int versionNo,
			String traceId) {
		return aiClient.post(PATH, request, InterviewBriefAiResponse.class,
				idempotencyKey(briefId, versionNo), traceId);
	}

	/** {@code {briefId}:{versionNo}} — 재생성 때 자동으로 달라진다(클래스 docblock 참고). */
	public static String idempotencyKey(UUID briefId, int versionNo) {
		return briefId + ":" + versionNo;
	}
}
