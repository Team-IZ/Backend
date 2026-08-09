package com.bigproject.backend.global.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * AI(FastAPI) 서버 호출의 <b>유일한 출구</b>. 도메인은 이 클래스만 거쳐 AI로 나간다.
 *
 * <p>도메인마다 {@code RestClient}를 직접 만들면 인증 헤더·타임아웃·에러 해석이 도메인 수만큼
 * 갈라진다. 특히 에러 해석이 갈라지면 같은 503을 어디서는 재시도하고 어디서는 안 하게 된다.
 *
 * <h2>AI가 내려보내는 에러 봉투가 두 가지다</h2>
 *
 * <pre>
 * 공통      : {error, message, retryable}                — 409·422 등
 * 면담 브리프 : {failureCode, message, aiUsage[]}          — 503 (§5.2, 계약이 다르다)
 * </pre>
 *
 * 둘 다 {@link AiCallException} 하나로 접는다 — 호출부가 봉투 모양을 알 필요는 없고
 * "무엇이 실패했고 다시 불러도 되는가"만 알면 된다.
 */
@Slf4j
@Component
public class AiClient {

	/** Spring→FastAPI 서비스 간 인증 헤더. AI 저장소 {@code app/api/deps.py:require_internal_key}. */
	public static final String INTERNAL_KEY_HEADER = "X-Internal-Key";

	/**
	 * 재시도 시 동일 값을 재사용한다. 다섯 엔드포인트가 같은 이름을 쓴다(백엔드 제안서 1-1).
	 * 옛 {@code X-Idempotency-Key}는 면담 브리프 전용 변종이라 폐기됐다.
	 */
	public static final String IDEMPOTENCY_KEY_HEADER = "idempotency-key";

	public static final String TRACE_ID_HEADER = "x-trace-id";

	/**
	 * 에러 본문 해석 전용. <b>컨테이너에서 주입받지 않는다.</b>
	 *
	 * <p>이 프로젝트에는 {@code ObjectMapper} 빈이 등록돼 있지 않다(지금까지 주입받는 코드가
	 * 없어 드러나지 않았다). 주입을 선언하면 애플리케이션 기동 자체가 실패한다.
	 *
	 * <p>자체 인스턴스를 두는 것이 손해가 아닌 이유: 여기서 하는 일은 에러 봉투에서
	 * {@code error}·{@code message}·{@code retryable} 세 값을 꺼내는 것뿐이라 매퍼 설정에
	 * 좌우되지 않는다. 요청·응답 본문의 직렬화는 {@code RestClient}의 메시지 컨버터가
	 * 따로 처리하므로 이 매퍼를 거치지 않는다.
	 */
	private static final ObjectMapper ERROR_BODY_MAPPER = new ObjectMapper();

	private final RestClient restClient;

	public AiClient(@Qualifier(AiClientConfig.AI_REST_CLIENT) RestClient restClient) {
		this.restClient = restClient;
	}

	/**
	 * AI에 POST하고 응답 본문을 {@code responseType}으로 받는다.
	 *
	 * @param idempotencyKey 재시도 판별 키. 면담 브리프는 <b>필수</b>이고 나머지 넷은 선택이지만,
	 *                       {@code ai_usage.idempotency_key}가 전역 UNIQUE라 어느 경로든 넣는 편이 낫다.
	 * @param traceId        분산 추적 ID. 그대로 {@code ai_usage.trace_id}로 돌아온다.
	 * @throws AiCallException 4xx·5xx 응답, 타임아웃, 연결 실패 전부
	 */
	public <T> T post(String path, Object body, Class<T> responseType, String idempotencyKey, String traceId) {
		try {
			return restClient.post()
					.uri(path)
					.contentType(MediaType.APPLICATION_JSON)
					.headers(headers -> {
						if (idempotencyKey != null && !idempotencyKey.isBlank()) {
							headers.set(IDEMPOTENCY_KEY_HEADER, idempotencyKey);
						}
						if (traceId != null && !traceId.isBlank()) {
							headers.set(TRACE_ID_HEADER, traceId);
						}
					})
					.body(body)
					.retrieve()
					.onStatus(HttpStatusCode::isError, (request, response) -> {
						throw translate(path, response.getStatusCode(), readBody(response.getBody()));
					})
					.body(responseType);
		} catch (ResourceAccessException exception) {
			// 연결 실패·읽기 타임아웃. 응답이 없으므로 상태 코드도 failureCode도 없다.
			// AI 서버가 살아 있는데 느린 것일 수 있어 재시도 가능으로 본다.
			throw new AiCallException(null, "TIMEOUT", true,
					"AI 서버에 닿지 못했습니다: " + path, exception);
		}
	}

	/**
	 * AI에 {@code multipart/form-data}로 POST한다. ZIP 제출 분석({@code POST /analyses})용이다.
	 *
	 * <p>파트를 {@code HttpEntity}로 감싸 넘기면 파트마다 {@code Content-Type}을 붙일 수 있고,
	 * 객체 파트는 {@code FormHttpMessageConverter}가 중첩 Jackson 컨버터로 직렬화한다.
	 * 여기서 JSON 문자열을 직접 만들지 않는 이유다 — 매퍼를 하나 더 두면 요청 본문 직렬화 규칙이
	 * JSON 경로({@link #post})와 갈라진다.
	 *
	 * @param parts {@code MultiValueMap}. 파일 파트는 길이를 아는 {@code Resource}여야 한다 —
	 *              {@code InputStream}을 넣으면 청크 전송이 되어 {@code Content-Length} 없는 파트를
	 *              거절하는 서버에서 실패한다.
	 */
	public <T> T postMultipart(String path, MultiValueMap<String, Object> parts, Class<T> responseType,
			String idempotencyKey, String traceId) {
		try {
			return restClient.post()
					.uri(path)
					.contentType(MediaType.MULTIPART_FORM_DATA)
					.headers(headers -> {
						if (idempotencyKey != null && !idempotencyKey.isBlank()) {
							headers.set(IDEMPOTENCY_KEY_HEADER, idempotencyKey);
						}
						if (traceId != null && !traceId.isBlank()) {
							headers.set(TRACE_ID_HEADER, traceId);
						}
					})
					.body(parts)
					.retrieve()
					.onStatus(HttpStatusCode::isError, (request, response) -> {
						throw translate(path, response.getStatusCode(), readBody(response.getBody()));
					})
					.body(responseType);
		} catch (ResourceAccessException exception) {
			throw new AiCallException(null, "TIMEOUT", true,
					"AI 서버에 닿지 못했습니다: " + path, exception);
		}
	}

	/**
	 * AI에 GET한다. 비동기 job 폴링({@code GET /reports/{jobId}})용이다.
	 *
	 * <p>폴링 경로는 멱등키를 싣지 않는다 — 상태 조회는 부수효과가 없어 중복 판별이 필요 없다.
	 */
	public <T> T get(String path, Class<T> responseType, String traceId) {
		try {
			return restClient.get()
					.uri(path)
					.headers(headers -> {
						if (traceId != null && !traceId.isBlank()) {
							headers.set(TRACE_ID_HEADER, traceId);
						}
					})
					.retrieve()
					.onStatus(HttpStatusCode::isError, (request, response) -> {
						throw translate(path, response.getStatusCode(), readBody(response.getBody()));
					})
					.body(responseType);
		} catch (ResourceAccessException exception) {
			throw new AiCallException(null, "TIMEOUT", true,
					"AI 서버에 닿지 못했습니다: " + path, exception);
		}
	}

	/**
	 * 에러 본문을 {@link AiCallException}으로 접는다.
	 *
	 * <p>재시도 판단은 <b>AI가 준 값을 우선</b>한다. 공통 봉투는 {@code retryable}을 직접 주고,
	 * 면담 브리프 503 봉투는 {@code failureCode}로 판단한다 — 계약 위반(INVALID_JSON)은 다시
	 * 불러도 모델이 같은 실수를 할 가능성이 높아 재시도로 치지 않는다.
	 */
	private AiCallException translate(String path, HttpStatusCode status, String rawBody) {
		String failureCode = null;
		String message = null;
		Boolean retryable = null;

		try {
			JsonNode node = ERROR_BODY_MAPPER.readTree(rawBody);
			if (node.hasNonNull("failureCode")) {
				failureCode = node.get("failureCode").asText();
			} else if (node.hasNonNull("error")) {
				failureCode = node.get("error").asText();
			}
			if (node.hasNonNull("message")) {
				message = node.get("message").asText();
			}
			if (node.hasNonNull("retryable")) {
				retryable = node.get("retryable").asBoolean();
			}
		} catch (Exception exception) {
			// 본문이 JSON이 아니면(프록시가 낸 HTML 등) 상태 코드만으로 판단한다.
			log.warn("AI 에러 본문을 해석하지 못했습니다: path={}, status={}", path, status);
		}

		boolean resolvedRetryable = retryable != null
				? retryable
				: isRetryable(status, failureCode);

		String resolvedMessage = message != null
				? message
				: "AI 호출이 실패했습니다: " + path + " (status=" + status + ")";

		log.warn("AI 호출 실패: path={}, status={}, failureCode={}, retryable={}",
				path, status, failureCode, resolvedRetryable);

		return new AiCallException(status, failureCode, resolvedRetryable, resolvedMessage);
	}

	private static boolean isRetryable(HttpStatusCode status, String failureCode) {
		// 계약 위반과 멱등 충돌은 다시 불러도 같다. 전송 계층 실패만 재시도 가치가 있다.
		if ("INVALID_JSON".equals(failureCode) || "IDEMPOTENCY_CONFLICT".equals(failureCode)) {
			return false;
		}
		if (status == null) {
			return true;
		}
		// 4xx는 우리 요청이 잘못된 것이라 그대로 다시 보내도 같은 결과다.
		return status.is5xxServerError();
	}

	private static String readBody(InputStream stream) {
		try (InputStream in = stream) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException exception) {
			return "";
		}
	}
}
