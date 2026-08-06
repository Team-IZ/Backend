package com.bigproject.backend.domain.submission.domain;

import java.util.UUID;

/**
 * 제출 요청의 멱등키. 클라이언트가 {@code Idempotency-Key} 헤더로 보낸다.
 *
 * <p><b>추적 ID와 혼동하면 안 된다.</b> 이 프로젝트의 다른 API가 쓰는 {@code X-Request-Id}는 HTTP 요청마다
 * 새로 만드는 추적용 값이고, 멱등키는 정반대로 <b>재시도해도 같은 값이 유지</b>되어야 의미가 있다.
 * 두 값을 한 헤더로 겸하면 프록시·게이트웨이가 흔히 {@code X-Request-Id}를 덮어쓸 때
 * 멱등성이 조용히 깨진다. 그래서 헤더를 나눴다.
 *
 * <p>서버가 대신 만들어 주지 않는다. 생략을 허용하고 서버가 임의 값을 채우면 멱등 판정이 항상 실패하는데,
 * 클라이언트에게는 그 사실이 보이지 않아 "중복 방지가 되고 있다"는 착각만 남는다.
 */
public final class IdempotencyKey {

	private IdempotencyKey() {
	}

	/**
	 * 헤더 원문을 UUID로 해석한다.
	 *
	 * <p>UUID만 허용하는 이유는 저장 위치가 UUID 컬럼이기 때문이다
	 * ({@code repository_verification.request_id}, {@code submission_artifact.request_id}).
	 * 임의 문자열을 받으면 저장 단계에서 실패해 원인을 알기 어려운 500이 된다.
	 */
	public static UUID parse(String rawHeaderValue) {
		if (rawHeaderValue == null || rawHeaderValue.isBlank()) {
			throw new SubmissionException(SubmissionErrorCode.IDEMPOTENCY_KEY_REQUIRED);
		}
		try {
			return UUID.fromString(rawHeaderValue.trim());
		} catch (IllegalArgumentException exception) {
			throw new SubmissionException(SubmissionErrorCode.IDEMPOTENCY_KEY_INVALID);
		}
	}
}
