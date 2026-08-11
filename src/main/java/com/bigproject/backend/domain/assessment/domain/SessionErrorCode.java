package com.bigproject.backend.domain.assessment.domain;

import org.springframework.http.HttpStatus;

/**
 * 검증 세션(TR-03) API의 에러 코드. 프론트가 메시지가 아니라 <b>코드</b>로 분기한다.
 *
 * <p>세션은 30~70분 전체화면이고 나가는 경로가 없다. 그래서 실패를 뭉뚱그리면 학생이 할 수 있는 일이
 * 없어진다 — "다시 시도하면 되는가"와 "이 시험은 끝났는가"를 코드로 갈라 준다.
 */
public enum SessionErrorCode {

	// ── 세션 접근 ──
	/** 세션이 없거나, 남의 세션이거나, 삭제됐다. 셋을 구분하지 않는다 — 남의 세션 존재를 알려줄 이유가 없다. */
	SESSION_NOT_ACCESSIBLE(HttpStatus.NOT_FOUND, "접근할 수 있는 세션을 찾을 수 없습니다."),
	/** COMPLETED·INTERRUPTED·INVALID·FAILED·SUPERSEDED. 끝난 시험에는 아무것도 쓸 수 없다. */
	SESSION_ALREADY_ENDED(HttpStatus.CONFLICT, "이미 끝난 세션입니다."),
	/** 아직 START를 부르지 않았다. 인트로 동의 없이 답을 받으면 정의서가 요구하는 고지 기록이 남지 않는다. */
	SESSION_NOT_STARTED(HttpStatus.CONFLICT, "아직 시작하지 않은 세션입니다."),
	/** 정책 시간 상한(기본 70분)을 넘겼다. 답한 데까지는 저장되고 세션은 닫힌다. */
	SESSION_TIMEOUT(HttpStatus.CONFLICT, "시간이 다 되어 세션이 종료되었습니다."),

	// ── 문제·단계 ──
	PROBLEM_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 문제를 찾을 수 없습니다."),
	/**
	 * 끝난 문제를 다시 열려고 했다. 정의서 §3 — "끝난 문제는 다시 열 수 없다. 지금 문제와 무관한 데
	 * 시간을 쓰고 '아까 그거 틀린 것 같은데'만 남는다."
	 */
	PROBLEM_ALREADY_CLOSED(HttpStatus.CONFLICT, "이미 끝난 문제는 다시 열 수 없습니다."),
	/** 커서가 가리키는 단계가 없다. 세션 준비가 깨졌다는 뜻이라 조용히 넘기지 않는다. */
	STAGE_NOT_FOUND(HttpStatus.CONFLICT, "진행할 단계를 찾을 수 없습니다."),

	// ── 답변 ──
	ANSWER_TEXT_REQUIRED(HttpStatus.BAD_REQUEST, "답변 내용이 필요합니다."),
	/** 같은 단계에 두 번 제출됐다. 낙관적 잠금(row_version) 충돌이므로 다시 불러오면 된다. */
	ANSWER_ALREADY_SUBMITTED(HttpStatus.CONFLICT, "이미 제출된 답변입니다."),

	// ── 관찰 신호 ──
	/** 이탈·연결 끊김·첫 타이핑 지연이 하나도 없는 요청이다. 받아 봐야 쓸 곳이 없다. */
	ACTIVITY_SIGNAL_REQUIRED(HttpStatus.BAD_REQUEST, "기록할 관찰 신호가 없습니다."),

	// ── 힌트 ──
	/** 단계당 2회를 다 썼다. 화면은 버튼을 문구로 바꾸므로 정상 흐름에서는 오지 않는다. */
	HINT_EXHAUSTED(HttpStatus.CONFLICT, "더 이상 설명해 드릴 수 없습니다."),
	/**
	 * 직전 답변이 <b>채점되어 미달</b>일 때만 힌트가 열린다. 아직 답하지 않았거나 이미 통과한 단계는
	 * 여기로 걸린다. DB CHECK({@code ck_problem_stage_*_hint_presented_at})와 같은 규칙이되, 그쪽은
	 * NULL 비교라 "아직 채점 전"을 걸러 주지 못해 여기서 막는다.
	 */
	HINT_NOT_AVAILABLE(HttpStatus.CONFLICT, "지금은 다시 설명을 받을 수 없습니다."),

	// ── AI ──
	/** 채점 실패. AI가 503으로 재전송을 요구한 경우다 — 같은 답을 그대로 다시 보내면 된다. */
	GRADING_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "채점에 실패했습니다. 잠시 후 다시 제출해 주세요.");

	private final HttpStatus status;
	private final String message;

	SessionErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	public HttpStatus status() {
		return status;
	}

	public String message() {
		return message;
	}
}
