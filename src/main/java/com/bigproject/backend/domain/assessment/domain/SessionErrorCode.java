package com.bigproject.backend.domain.assessment.domain;

import com.bigproject.backend.global.exception.ApiErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 검증 세션(TR-03) API의 에러 코드. 프론트가 메시지가 아니라 <b>코드</b>로 분기한다.
 *
 * <p>세션은 20~60분 전체화면이고 나가는 경로가 없다. 그래서 실패를 뭉뚱그리면 학생이 할 수 있는 일이
 * 없어진다 — "다시 시도하면 되는가"와 "이 시험은 끝났는가"를 코드로 갈라 준다.
 *
 * <p>{@link ApiErrorCode}를 구현하는 이유는 {@code SwaggerConfig}가 카탈로그에서 상태·기본 메시지를
 * 읽어 <b>각 오류 응답의 예시 본문</b>을 만들기 때문이다. 구현하지 않으면 세션 코드만 예시 메시지가
 * "요청을 처리할 수 없습니다."로 뭉개져, 화면이 어떤 문구를 폴백으로 써야 하는지 문서에서 알 수 없다.
 */
public enum SessionErrorCode implements ApiErrorCode {

	// ── 세션 접근 ──
	/** 세션이 없거나, 남의 세션이거나, 삭제됐다. 셋을 구분하지 않는다 — 남의 세션 존재를 알려줄 이유가 없다. */
	SESSION_NOT_ACCESSIBLE(HttpStatus.NOT_FOUND, "접근할 수 있는 세션을 찾을 수 없습니다."),
	/** COMPLETED·INTERRUPTED·INVALID·FAILED·SUPERSEDED. 끝난 시험에는 아무것도 쓸 수 없다. */
	SESSION_ALREADY_ENDED(HttpStatus.CONFLICT, "이미 끝난 세션입니다."),
	/** 아직 START를 부르지 않았다. 인트로 동의 없이 답을 받으면 정의서가 요구하는 고지 기록이 남지 않는다. */
	SESSION_NOT_STARTED(HttpStatus.CONFLICT, "아직 시작하지 않은 세션입니다."),
	/**
	 * 개인 응시 창({@code measurement_attempt.assessment_close_at})이 닫혔다.
	 *
	 * <p>세션을 <b>닫지는 않는다</b> — 거절만 한다. 창이 지나 응시하지 못한 사람을 어떤 종료 상태로
	 * 남길지는 아직 정해지지 않았고({@code NOT_ATTENDED}를 쓰는 코드가 없다), 지금
	 * {@code JdbcSessionRepository#end}로 닫으면 한 번도 못 푼 학생이 {@code COMPLETED}로 기록된다.
	 * 여기서는 <b>닫힌 시험에 쓰기가 통과하던 구멍만</b> 막는다.
	 */
	ASSESSMENT_WINDOW_CLOSED(HttpStatus.CONFLICT, "응시 창이 닫혀 더 진행할 수 없습니다."),
	/**
	 * 다시 보기 마감({@code measurement_attempt.review_due_at})이 지났다.
	 *
	 * <p>응시 창 만료와 코드를 가르는 이유는 <b>학생이 할 수 있는 일이 다르기</b> 때문이다 —
	 * 응시 창은 매니저에게 문의할 여지가 있고, 다시 보기는 회차당 한 번뿐이라 그것으로 끝이다.
	 */
	REVIEW_DUE_AT_PASSED(HttpStatus.CONFLICT, "다시 보기 마감이 지났습니다."),
	/** 정책 시간 상한(기본 60분)을 넘겼다. 답한 데까지는 저장되고 세션은 닫힌다. */
	SESSION_TIMEOUT(HttpStatus.CONFLICT, "시간이 다 되어 세션이 종료되었습니다."),
	/**
	 * 문제별 제한(기본 20분)을 넘겼다. 힌트를 다 쓰고도 미달일 때와 같은 전이로 문제가 닫힌다 —
	 * 세션은 끝나지 않고 다음 문제로 넘어가거나(마지막 문제였으면 세션이 끝난다).
	 */
	PROBLEM_TIME_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "이 문제의 제한 시간이 지나 다음 문제로 넘어갔습니다."),

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
	 * 힌트는 답하기 전에도 열 수 있고 미달 후에는 자동으로도 열린다. 그래서 여기로 걸리는 것은
	 * <b>끝난 질문</b>(통과했거나 마지막 힌트까지 쓰고 미달이라 {@code NOT_PASSED}로 닫혔다)과
	 * 다시 보기뿐이다 — 둘 다 보여 줄 화면이 없다.
	 */
	HINT_NOT_AVAILABLE(HttpStatus.CONFLICT, "지금은 다시 설명을 받을 수 없습니다."),

	// ── 다시 보기 파생 ──
	/**
	 * 리포트가 없거나, 남의 리포트이거나, 아직 공개되지 않았거나, 활성 스냅샷이 없다. 넷을 구분하지
	 * 않는다 — 남의 리포트 존재를 알려줄 이유가 없고, 화면이 할 일은 어느 쪽이든 같다.
	 */
	REVIEW_REPORT_NOT_ACCESSIBLE(HttpStatus.NOT_FOUND, "다시 보기를 열 수 있는 리포트를 찾을 수 없습니다."),
	/**
	 * 1차 응시가 끝나지 않았거나 세션이 없다. 도달 단계가 확정되지 않아 다시 볼 문제를 고를 수 없다.
	 */
	REVIEW_SOURCE_NOT_READY(HttpStatus.CONFLICT, "1차 응시가 끝나지 않아 다시 보기를 열 수 없습니다."),
	/** 기준 단계 미만인 문제가 하나도 없다. 다시 볼 것이 없다는 뜻이라 오류가 아니라 안내다. */
	REVIEW_NOT_ELIGIBLE(HttpStatus.CONFLICT, "다시 볼 문제가 없습니다."),
	/** 이미 끝낸 다시 보기가 있다. 회차당 한 번이다. */
	REVIEW_ALREADY_COMPLETED(HttpStatus.CONFLICT, "이 회차의 다시 보기는 이미 끝났습니다."),

	// ── AI ──
	/** 채점 실패. AI가 503으로 재전송을 요구한 경우다 — 같은 답을 그대로 다시 보내면 된다. */
	GRADING_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "채점에 실패했습니다. 잠시 후 다시 제출해 주세요.");

	private final HttpStatus status;
	private final String message;

	SessionErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	@Override
	public HttpStatus status() {
		return status;
	}

	public String message() {
		return message;
	}

	/** {@link ApiErrorCode} 계약. {@link #message()}와 같은 값이며 문서 예시가 이쪽을 읽는다. */
	@Override
	public String defaultMessage() {
		return message;
	}
}
