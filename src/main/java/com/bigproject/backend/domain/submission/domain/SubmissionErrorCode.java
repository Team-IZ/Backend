package com.bigproject.backend.domain.submission.domain;

import org.springframework.http.HttpStatus;

/**
 * 코드 제출·분석 조회 API의 에러 코드. 프론트가 메시지가 아니라 <b>코드</b>로 분기한다.
 *
 * <p>저장소 접근 실패(REPO_NOT_FOUND 등)는 여기 없다. 백엔드가 GitHub에 접근하지 않으므로 제출 시점에는
 * 알 수 없고, 마감 후 분석 단계의 사건으로 {@code analysis_job.failure_code}에 기록되어
 * {@code GET /submissions/{id}/analysis}로 노출된다(2026-08-06 확정).
 */
public enum SubmissionErrorCode {

	// ── 제출 대상 ──
	/** 회차가 없거나, 삭제됐거나, 호출자가 그 회차 프로젝트의 유효 팀 구성원이 아니다. 셋을 구분하지 않는다. */
	SUBMISSION_ROUND_NOT_ACCESSIBLE(HttpStatus.NOT_FOUND, "제출할 수 있는 회차를 찾을 수 없습니다."),
	/** PLANNED·CLOSED·COMPLETED 회차. View의 can_submit이 round_status를 보지 않아 서버가 직접 막는다. */
	SUBMISSION_ROUND_NOT_OPEN(HttpStatus.CONFLICT, "지금은 제출할 수 있는 회차가 아닙니다."),
	SUBMISSION_DEADLINE_PASSED(HttpStatus.CONFLICT, "제출 마감이 지났습니다."),

	// ── 멱등키 ──
	/**
	 * 서버가 대신 만들어 주지 않는다. 생략을 허용하면 멱등 판정이 항상 실패하는데 클라이언트에게는
	 * 그 사실이 보이지 않아, 중복 제출이 생긴 뒤에야 발견된다.
	 */
	IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key 헤더가 필요합니다."),
	/** 저장 위치가 UUID 컬럼이라 임의 문자열은 받을 수 없다. */
	IDEMPOTENCY_KEY_INVALID(HttpStatus.BAD_REQUEST, "Idempotency-Key는 UUID 형식이어야 합니다."),
	/**
	 * 같은 키가 다른 대상에 재사용됐다. 최초 요청의 결과를 그대로 돌려주면 교육생은 방금 고른 회차에
	 * 제출했다고 믿지만 실제로는 이전 회차 제출을 보게 된다.
	 */
	IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "이미 다른 요청에 사용된 Idempotency-Key입니다."),

	// ── 제출 수단 ──
	SUBMISSION_METHOD_NOT_ALLOWED(HttpStatus.CONFLICT, "기관이 허용하지 않는 제출 수단입니다."),
	/** 형식·호스트 검사 실패. repository_verification.failure_code의 같은 이름 값과 문자열을 맞춘다. */
	INVALID_REPOSITORY_URL(HttpStatus.BAD_REQUEST, "저장소 주소 형식이 올바르지 않습니다."),
	UNSUPPORTED_HOST(HttpStatus.BAD_REQUEST, "GitHub 저장소 주소만 제출할 수 있습니다."),

	// ── ZIP ──
	/** submission_artifact.validation_failure_code의 같은 이름 값과 문자열을 맞춘다. */
	FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "허용 크기를 넘는 파일입니다."),
	ARCHIVE_INVALID(HttpStatus.BAD_REQUEST, "ZIP 파일을 열 수 없습니다."),
	ARTIFACT_STORE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "제출 파일을 저장하지 못했습니다."),

	// ── 조회 ──
	SUBMISSION_NOT_FOUND(HttpStatus.NOT_FOUND, "제출을 찾을 수 없습니다."),
	/** 매니저 제출 현황 조회. 프로젝트가 없거나, 삭제됐거나, 그 번호의 회차가 없다. 셋을 구분하지 않는다. */
	PROJECT_ROUND_NOT_FOUND(HttpStatus.NOT_FOUND, "프로젝트 회차를 찾을 수 없습니다."),
	/**
	 * 분석이 아직 성공하지 않아 결과가 없다. 진행 중·실패와 구분하려면
	 * {@code GET /submissions/{submissionId}/analysis}의 {@code phase}를 본다.
	 */
	ANALYSIS_RESULT_NOT_FOUND(HttpStatus.NOT_FOUND, "분석 결과가 아직 없습니다."),
	SUBMISSION_ACCESS_DENIED(HttpStatus.FORBIDDEN, "다른 팀의 제출은 조회할 수 없습니다."),

	// ── AI 연동 ──
	/**
	 * 제출 접수 전 AI 프록시 헬스체크가 실패했다(2026-08-11).
	 *
	 * <p>제출 자체는 DB 작업이라 받아 둘 수도 있지만, 받아 두면 분석이 조용히 실패하고 교육생은 마감이
	 * 지난 뒤에야 안다. 지금 막으면 즉시 알고 다시 시도한다 — 그래서 4xx가 아니라 <b>재시도하라는</b>
	 * 503이다.
	 */
	AI_SERVER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 서버에 연결할 수 없어 지금은 제출할 수 없습니다.");

	private final HttpStatus status;
	private final String defaultMessage;

	SubmissionErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	public HttpStatus status() {
		return status;
	}

	public String defaultMessage() {
		return defaultMessage;
	}
}
