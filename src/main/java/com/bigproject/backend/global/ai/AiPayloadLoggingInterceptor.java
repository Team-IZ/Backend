package com.bigproject.backend.global.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * AI 서버와 주고받은 <b>원문</b>을 로그로 남긴다.
 *
 * <p>이게 없으면 {@code POST /analyses}가 202를 줬는데 jobId가 비는 경우에 원인을 가릴 수 없다 —
 * AI가 그 필드를 안 보낸 것인지, 이름이 달라({@code job_id} 등) 우리 매핑이 못 읽은 것인지가
 * 예외 메시지만으로는 똑같아 보인다. {@link AiClient}는 본문을 곧바로 객체로 바꾸므로 원문이
 * 남지 않는다.
 *
 * <h2>헤더는 찍지 않는다</h2>
 *
 * <p>{@code X-Internal-Key}가 헤더에 실려 있다. 헤더를 통째로 찍으면 공유 비밀이 로그로 샌다.
 * 대조에 필요한 {@code x-trace-id}만 뽑아 쓴다 — 이 값이 {@code analysis_job.trace_id}와 같아서
 * DB 행과 로그를 이어 볼 수 있다.
 *
 * <h2>본문은 잘라서 찍는다</h2>
 *
 * <p>분석 결과 응답은 문제·힌트·커밋 이력이 전부 실려 수백 KB가 된다. 통째로 찍으면 로그가
 * 그것만으로 채워진다. 접수 응답({@code {jobId, status}})은 {@link #MAX_BODY_CHARS} 안에 다 들어온다.
 *
 * <p>ZIP 제출의 multipart 요청 본문은 아예 찍지 않는다. 바이너리라 읽을 수 없고 수십 MB다.
 */
@Slf4j
public class AiPayloadLoggingInterceptor implements ClientHttpRequestInterceptor {

	/** 잘라내는 길이. 접수 응답은 이 안에 전부 들어온다. */
	private static final int MAX_BODY_CHARS = 2000;

	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body,
			ClientHttpRequestExecution execution) throws IOException {
		String traceId = request.getHeaders().getFirst(AiClient.TRACE_ID_HEADER);
		log.info("AI 요청 ▶ {} {} traceId={} body={}",
				request.getMethod(), request.getURI(), traceId, requestBody(request, body));

		ClientHttpResponse response = execution.execute(request, body);

		// 여기서 본문을 읽을 수 있는 것은 AiClientConfig 가 BufferingClientHttpRequestFactory 로
		// 감쌌기 때문이다. 안 감싸면 이 읽기가 스트림을 소진해 메시지 컨버터가 빈 본문을 받는다.
		log.info("AI 응답 ◀ {} {} status={} body={}",
				request.getMethod(), request.getURI(), response.getStatusCode(), responseBody(response));
		return response;
	}

	private static String requestBody(HttpRequest request, byte[] body) {
		MediaType contentType = request.getHeaders().getContentType();
		if (contentType != null && MediaType.MULTIPART_FORM_DATA.isCompatibleWith(contentType)) {
			return "<multipart " + body.length + " bytes>";
		}
		return truncate(new String(body, StandardCharsets.UTF_8));
	}

	private static String responseBody(ClientHttpResponse response) {
		try (var in = response.getBody()) {
			return truncate(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException exception) {
			// 본문을 못 읽었다고 호출을 실패시키지 않는다. 이 인터셉터는 관찰용이다.
			return "<본문을 읽지 못했습니다: " + exception.getMessage() + ">";
		}
	}

	private static String truncate(String text) {
		if (text.length() <= MAX_BODY_CHARS) {
			return text;
		}
		return text.substring(0, MAX_BODY_CHARS) + "...<" + text.length() + "자 중 앞부분만>";
	}
}
