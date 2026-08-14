package com.bigproject.backend.domain.intervention.domain;

import com.bigproject.backend.global.exception.ApiErrorCode;
import org.springframework.http.HttpStatus;

public enum InterventionErrorCode implements ApiErrorCode {

	/**
	 * 담당 반이 아니거나 존재하지 않는다. <b>둘을 구분하지 않는다</b> — 담당 밖 케이스에
	 * 403을 주면 "그 회차에 그런 교육생이 있다"는 사실이 새어 나간다.
	 */
	INTERVIEW_CASE_NOT_FOUND(HttpStatus.NOT_FOUND, "담당 범위에서 면담 대상을 찾을 수 없습니다."),

	/**
	 * 면담이 이미 시작·종결됐다. {@code interview_candidate} COMMENT가 제외를
	 * "연결된 면담이 PENDING인 후보"로 한정한다 — 이미 만난 사람을 이번 회차 대상에서
	 * 빼는 것은 상태 모델상 의미가 없다.
	 */
	INTERVIEW_ALREADY_STARTED(HttpStatus.CONFLICT, "이미 시작되었거나 종결된 면담은 제외할 수 없습니다."),

	/** 제외 상태가 아닌데 되돌리기를 호출했다(또는 그 반대). */
	INTERVIEW_EXCLUSION_STATE_CONFLICT(HttpStatus.CONFLICT, "현재 상태에서는 처리할 수 없습니다."),

	/** 다른 요청이 먼저 상태를 바꿨다. {@code row_version} 재검증 실패. */
	INTERVIEW_ROW_VERSION_CONFLICT(HttpStatus.CONFLICT, "면담 대상 상태가 변경되었습니다. 다시 조회해 주세요."),

	/**
	 * 아직 브리프를 만들지 않았다. <b>조회가 만들지 않는다</b> — 생성은 쓰기가 세 겹으로
	 * 일어나는 동작이라 별도 {@code POST}가 갖는다. 화면은 이 응답을 받으면
	 * {@code [브리프 생성]} 버튼을 그린다.
	 */
	INTERVIEW_BRIEF_NOT_CREATED(HttpStatus.NOT_FOUND, "아직 생성되지 않은 브리프입니다."),

	/**
	 * 무효 응시인데 아직 사람이 판정하지 않았다. <b>무효 확인이 브리프보다 먼저다</b>
	 * (정의서 §5) — 판정 전에는 {@code briefType}(STANDARD/INVALID_ATTEMPT)을 정할 수 없어
	 * 여는 말과 질문이 통째로 어긋난다.
	 */
	VALIDITY_REVIEW_REQUIRED(HttpStatus.CONFLICT, "무효 확인을 먼저 처리해야 브리프를 만들 수 있습니다."),

	/**
	 * AI 생성 실패 중 <b>다시 불러도 같은</b> 경우. 계약 위반(INVALID_JSON)이나 멱등 충돌이다.
	 * 화면은 재시도를 권하지 않는다.
	 */
	BRIEF_GENERATION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "브리프를 생성하지 못했습니다."),

	/** AI 생성 실패 중 <b>재시도 가치가 있는</b> 경우. 타임아웃·레이트리밋·프로바이더 오류다. */
	BRIEF_GENERATION_FAILED_RETRYABLE(HttpStatus.SERVICE_UNAVAILABLE, "브리프 생성이 지연되고 있습니다. 다시 시도해 주세요."),

	/**
	 * 확정하려는데 선택된 질문이 없다. 테이블 COMMENT가
	 * "CONFIRMED 전환 시 선택 항목 1건 이상"을 요구한다 — 질문 0개짜리 브리프로 면담을
	 * 종결하면 나중에 "무엇을 물었는지"가 남지 않는다.
	 */
	BRIEF_HAS_NO_SELECTED_ITEM(HttpStatus.CONFLICT, "질문이 없는 브리프는 확정할 수 없습니다."),

	/**
	 * 종결된 면담의 브리프는 읽기 전용이다. 지난 면담에서 실제로 무엇을 물었는지가
	 * 다음 회차 브리프의 {@code askedQuestions}로 이어지므로 사후에 바꾸면 그 기록이
	 * 사실과 달라진다. 원인·기록은 여전히 고칠 수 있다.
	 */
	BRIEF_NOT_EDITABLE(HttpStatus.CONFLICT, "종결된 면담의 브리프는 다시 만들 수 없습니다."),

	/**
	 * 인증 정보에 기관 ID가 없다. 토큰 발급 쪽 문제라 사용자가 할 수 있는 일이 없다 —
	 * 다른 도메인({@code AcademicOperationsErrorCode})과 같은 코드명을 쓴다.
	 */
	ORGANIZATION_CONTEXT_MISSING(HttpStatus.INTERNAL_SERVER_ERROR, "인증 정보에서 기관을 확인할 수 없습니다.");

	private final HttpStatus status;
	private final String defaultMessage;

	InterventionErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	@Override public HttpStatus status() { return status; }
	@Override public String defaultMessage() { return defaultMessage; }
}
