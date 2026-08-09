package com.bigproject.backend.domain.academicoperations.domain;

import com.bigproject.backend.global.exception.ApiErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 기수·반·배정 흐름이 내려보내는 에러 코드.
 *
 * <p>전에는 아홉 개 오퍼레이션이 전부 {@code NOT_FOUND}·{@code CONFLICT}·{@code BAD_REQUEST}
 * 셋 중 하나로만 실패했다. 상태 코드에 이미 있는 정보라 클라이언트가 새로 알 수 있는 것이 없었고,
 * 화면은 {@code message} 문자열을 비교하는 수밖에 없었다 — 문구를 다듬는 순간 조용히 깨지는 분기다.
 *
 * <p>특히 <b>배정 흐름</b>이 문제였다. "기수에 없는 교육생", "되돌릴 배정이 없음", "정원 초과"가
 * 같은 400으로 나가는데 화면이 해야 할 일은 각각 다르다 — 명단을 다시 불러오거나, 이미 처리된
 * 것이라 알리거나, 반을 다시 고르게 해야 한다.
 */
public enum AcademicOperationsErrorCode implements ApiErrorCode {

	// ── 기수 ──

	COHORT_NOT_FOUND(HttpStatus.NOT_FOUND, "기수를 찾을 수 없습니다."),
	COHORT_NAME_TAKEN(HttpStatus.CONFLICT, "이미 있는 기수명입니다."),
	/** 시작일이 종료일보다 뒤다. 입력 오류라 화면은 날짜 칸에 사유를 붙인다. */
	COHORT_PERIOD_INVALID(HttpStatus.BAD_REQUEST, "시작일은 종료일보다 늦을 수 없습니다."),
	/** 요청한 기수가 이 기관 소속이 아니다. 존재 여부는 알려 주지 않는다. */
	COHORT_NOT_IN_ORGANIZATION(HttpStatus.BAD_REQUEST, "기관에 속하지 않은 기수입니다."),

	/**
	 * 개강한(또는 종료된) 기수라 이름·기간을 고칠 수 없다(11차 Q2).
	 *
	 * <p>개강 후에 기간을 바꾸면 이미 그 기간을 기준으로 발행된 리포트·회차 일정과 어긋난다.
	 * 화면은 {@code PLANNED}에서만 수정 버튼을 연다.
	 */
	COHORT_NOT_MUTABLE(HttpStatus.CONFLICT, "개강한 기수는 수정할 수 없습니다."),

	/**
	 * 지울 수 없는 기수다(11차 Q2). 개강했거나, 명단·반·회차가 이미 붙어 있다.
	 *
	 * <p>반 삭제({@code CLASSROOM_NOT_DELETABLE})와 같이 <b>사유를 코드로 쪼개지 않는다</b> —
	 * 어느 쪽이든 화면이 할 일은 "지울 수 없습니다"를 보여주고 버튼을 잠그는 것 하나다.
	 * 무엇이 붙어 있는지는 {@code message}에 담는다.
	 */
	COHORT_NOT_DELETABLE(HttpStatus.CONFLICT, "이미 사용 중인 기수는 삭제할 수 없습니다."),

	/** 수정 요청에 바꿀 값이 하나도 없다. 빈 PATCH는 아무 일도 하지 않으므로 입력 오류로 돌려준다. */
	COHORT_UPDATE_EMPTY(HttpStatus.BAD_REQUEST, "수정할 값이 없습니다."),

	// ── 반 ──

	CLASSROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "반을 찾을 수 없습니다."),
	CLASSROOM_NAME_TAKEN(HttpStatus.CONFLICT, "이미 있는 반 이름입니다."),
	/**
	 * 반에 이미 팀이 편성됐거나 리포트가 만들어져 지울 수 없다(9차 R6).
	 *
	 * <p>사유를 코드로 쪼개지 않은 것은 <b>화면이 할 일이 하나</b>이기 때문이다 — 어느 쪽이든
	 * "지울 수 없습니다"를 보여주고 삭제 버튼을 잠근다. 무엇이 붙어 있는지는 {@code message}에 담는다.
	 */
	CLASSROOM_NOT_DELETABLE(HttpStatus.CONFLICT, "이미 사용 중인 반은 삭제할 수 없습니다."),
	/** 수정 요청에 바꿀 값이 하나도 없다. 빈 PATCH는 아무 일도 하지 않으므로 입력 오류로 돌려준다. */
	CLASSROOM_UPDATE_EMPTY(HttpStatus.BAD_REQUEST, "수정할 값이 없습니다."),

	// ── 배정 ──

	/**
	 * 요청한 교육생 중 이 기수에 유효하게 소속되지 않은 사람이 있다. 일부만 처리하지 않고
	 * <b>배정 전체를 거부</b>한다 — 화면은 명단을 다시 불러와야 한다.
	 */
	TRAINEE_NOT_IN_COHORT(HttpStatus.BAD_REQUEST, "기수에 속하지 않은 교육생입니다."),
	/**
	 * 되돌릴 활성 배정이 없다. 대개 이미 되돌렸거나 다른 사람이 먼저 처리한 경우라,
	 * 입력 오류가 아니라 <b>상태가 이미 그렇다</b>는 안내가 나가야 한다.
	 */
	ASSIGNMENT_NOT_FOUND(HttpStatus.BAD_REQUEST, "되돌릴 반 배정이 없는 교육생입니다."),

	// ── 공통 ──

	/** 인증 컨텍스트에 기관이 없다. 토큰 발급 쪽 결함이라 5xx다. */
	ORGANIZATION_CONTEXT_MISSING(HttpStatus.INTERNAL_SERVER_ERROR, "기관 컨텍스트를 확인할 수 없습니다.");

	private final HttpStatus status;
	private final String defaultMessage;

	AcademicOperationsErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	@Override
	public HttpStatus status() {
		return status;
	}

	@Override
	public String defaultMessage() {
		return defaultMessage;
	}
}
