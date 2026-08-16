package com.bigproject.backend.domain.reporting.domain;

import com.bigproject.backend.global.exception.ApiErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Reporting 도메인이 내려보내는 에러 코드. 프론트가 <b>코드</b>로 분기하므로 코드가 계약이다
 * (organization 도메인과 같은 규칙 — 메시지는 바꿔도 되고 코드는 못 바꾼다).
 *
 * <p>화면 대응: OP-05 리포트({@code #cases-op05}) · TR-04 내 리포트({@code #cases-tr04}).
 */
public enum ReportErrorCode implements ApiErrorCode {

	// ── 공통 ──
	REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "리포트를 찾을 수 없습니다."),
	/**
	 * 리포트 행은 있는데 활성 스냅샷이 없다. 생성이 실패했거나 아직 안 끝난 상태다.
	 * 발행 전(publishedAt=NULL)과 구분한다 — 이쪽은 <b>있어야 할 본문이 없는</b> 이상 상태다.
	 */
	REPORT_SNAPSHOT_NOT_FOUND(HttpStatus.CONFLICT, "리포트 본문이 아직 만들어지지 않았습니다."),
	/** 스냅샷 payload를 읽지 못했다. 스키마 버전이 코드보다 앞서거나 저장이 깨진 경우다. */
	REPORT_PAYLOAD_UNREADABLE(HttpStatus.INTERNAL_SERVER_ERROR, "리포트 본문을 읽지 못했습니다."),
	/** 남의 리포트를 조회하려 했다. 교육생은 자기 것만 본다. */
	REPORT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "다른 사람의 리포트는 조회할 수 없습니다."),

	// ── TR-04 내 리포트 ──
	/**
	 * 발행은 됐지만 공개 범위가 정해지지 않았다(trainee_release_status=NOT_CONFIGURED).
	 * 화면은 이 코드로 `공개 범위 미지정` 상태를 그린다 — 빈 리포트로 그리면 안 된다.
	 */
	REPORT_DISCLOSURE_NOT_CONFIGURED(HttpStatus.CONFLICT, "리포트 공개 범위가 아직 정해지지 않았습니다."),
	/** 공개 범위가 PRIVATE이라 교육생이 본문을 볼 수 없다. */
	REPORT_WITHHELD(HttpStatus.FORBIDDEN, "이 리포트는 공개되지 않았습니다."),
	/**
	 * RELEASED로 전이하면서 PRIVATE을 넘겼다. DB CHECK가 막기 전에 도메인에서 끊는다 —
	 * "공개하는데 범위가 비공개"는 뜻이 성립하지 않는다. 비공개는 withhold다.
	 */
	REPORT_DISCLOSURE_SCOPE_INVALID(HttpStatus.BAD_REQUEST, "공개 범위는 SUMMARY 또는 FULL이어야 합니다."),

	// ── OP-05 리포트 ──
	/** 기수에 수업 진단 리포트가 아직 없다. 회차가 하나도 안 끝났을 때 정상적으로 발생한다. */
	COHORT_REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "기수 리포트를 찾을 수 없습니다."),

	// ── 강제 생성(연동 시험 전용) ──
	/** 그 {@code session_id}로 세션을 찾지 못했다. 강제 생성 경로는 이것 말고 거르는 조건이 없다. */
	REPORT_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "세션을 찾을 수 없습니다."),
	/**
	 * {@code problem_stage}에 문제가 하나도 없는 세션이다. 만들 리포트가 없다 —
	 * run 행만 남기면 영원히 확정되지 않는 실행이 된다.
	 */
	REPORT_SESSION_HAS_NO_PROBLEM(HttpStatus.CONFLICT, "이 세션에는 채점된 문제가 없습니다."),
	/**
	 * 이미 진행 중인 수동 실행이 있다({@code uq_report_generation_run_active}).
	 * 오류가 아니라 "이미 돌고 있다"이므로 폴링이 끝나기를 기다리면 된다.
	 */
	REPORT_GENERATION_ALREADY_RUNNING(HttpStatus.CONFLICT, "이미 진행 중인 리포트 생성이 있습니다."),
	/**
	 * {@code ai.report.model-code}가 가리키는 모델이 {@code ai_model}에 ACTIVE로 없다.
	 * 설정 문제이지 요청 문제가 아니다.
	 */
	REPORT_MODEL_NOT_CONFIGURED(HttpStatus.INTERNAL_SERVER_ERROR, "리포트 생성 모델이 설정되지 않았습니다.");

	private final HttpStatus status;
	private final String defaultMessage;

	ReportErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	public HttpStatus status() {
		return status;
	}

	@Override
	public String defaultMessage() {
		return defaultMessage;
	}
}
