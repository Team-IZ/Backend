package com.bigproject.backend.domain.analytics.domain;

import com.bigproject.backend.global.exception.ApiErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 분석 격자·진행 현황 조회가 내려보내는 에러 코드.
 *
 * <p>전에는 이 화면들의 실패가 전부 {@code BAD_REQUEST}·{@code NOT_FOUND}·{@code FORBIDDEN}
 * 셋 중 하나로만 나갔다. 상태 코드에 이미 있는 정보라 클라이언트가 새로 알 수 있는 것이 없었고,
 * 화면은 {@code message} 문자열을 비교하는 수밖에 없었다 — 문구를 다듬는 순간 조용히 깨지는 분기다.
 *
 * <p>특히 <b>위험 교육생 비율</b>의 400이 문제였다. "기수에 없는 반", "기수의 미니프로젝트가 아님",
 * "회차 범위 오류", "팀 계층인데 프로젝트를 안 골랐음"이 같은 400으로 나가는데 화면이 해야 할 일은
 * 각각 다르다 — 반 필터를 비우거나, 프로젝트를 다시 고르게 하거나, 회차 드롭다운을 되돌리거나,
 * 팀 탭에서 프로젝트 선택을 먼저 요구해야 한다.
 *
 * <p><b>반별 진행 현황(Project)도 이 enum을 쓴다.</b> 그쪽 서비스가 이미 {@code AnalyticsActorGuard}를
 * 공유하므로 접근 실패 코드가 같아야 하고, 도메인마다 같은 뜻의 코드를 새로 만들면 프론트 유니온에
 * 동의어가 늘어난다.
 *
 * <p>기수·반을 찾지 못한 경우는 여기에 두지 않고 {@code AcademicOperationsErrorCode}의
 * {@code COHORT_NOT_FOUND}·{@code CLASSROOM_NOT_FOUND}를 그대로 쓴다. 뜻이 완전히 같은 코드를
 * 도메인마다 새로 만들면 프론트가 분기해야 할 값만 늘고, 카탈로그에서는 이름이 겹쳐 충돌한다.
 */
public enum AnalyticsErrorCode implements ApiErrorCode {

	// ── 공통 접근(AnalyticsActorGuard) ──

	/** 토큰은 유효한데 그 계정이 없다. 화면이 할 일은 조용히 로그인 화면으로 보내는 것뿐이다. */
	ANALYTICS_VIEWER_NOT_FOUND(HttpStatus.UNAUTHORIZED, "인증 사용자를 찾을 수 없습니다."),
	/** 계정이 정지됐거나 이메일 인증 전이다. 재시도해도 같으므로 화면은 문의를 안내한다. */
	ANALYTICS_VIEWER_NOT_ACTIVE(HttpStatus.FORBIDDEN, "활성 사용자만 분석 정보를 조회할 수 있습니다."),
	/** 계정은 멀쩡한데 소속 기관이 정지·삭제 대기다. 사용자 잘못이 아니라 안내 문구가 다르다. */
	ANALYTICS_ORGANIZATION_NOT_ACTIVE(HttpStatus.FORBIDDEN, "활성 기관의 사용자만 분석 정보를 조회할 수 있습니다."),
	/** 오퍼레이터·매니저가 아니다. 화면은 애초에 메뉴를 보여주지 말아야 한다. */
	ANALYTICS_ROLE_NOT_ALLOWED(HttpStatus.FORBIDDEN, "이 분석 정보를 조회할 권한이 없습니다."),
	/** 다른 기관의 기수다. 존재 여부는 알려 주지 않는다. */
	ANALYTICS_COHORT_CROSS_ORGANIZATION(HttpStatus.FORBIDDEN, "다른 기관의 기수는 조회할 수 없습니다."),

	// ── 위험 교육생 비율 ──

	/** 필터로 넘어온 반이 이 기수 소속이 아니다. 화면은 반 필터를 비우고 다시 불러와야 한다. */
	CLASSROOM_NOT_IN_COHORT(HttpStatus.BAD_REQUEST, "해당 기수에 속한 반이 아닙니다."),
	/** 지정한 프로젝트가 이 기수의 미니프로젝트가 아니다. 빅프로젝트를 고른 경우도 여기다. */
	PROJECT_NOT_IN_COHORT(HttpStatus.BAD_REQUEST, "해당 기수의 미니프로젝트가 아닙니다."),
	/** 시작 회차가 종료 회차보다 뒤이거나 1 미만이다. 화면은 회차 드롭다운을 되돌린다. */
	ROUND_RANGE_INVALID(HttpStatus.BAD_REQUEST, "회차 범위가 올바르지 않습니다."),
	/**
	 * 팀 계층인데 프로젝트를 지정하지 않았다. 회차 번호가 프로젝트마다 1부터 다시 시작해
	 * 팀 추이를 이을 수 없다 — 화면은 팀 탭에서 프로젝트 선택을 먼저 요구해야 한다.
	 */
	TEAM_LEVEL_PROJECT_REQUIRED(HttpStatus.BAD_REQUEST, "팀 계층은 프로젝트를 지정해야 합니다."),
	/** 팀 계층인데 반이 0개거나 2개 이상이다. 팀 번호는 반 안에서만 유일하다. */
	TEAM_LEVEL_SINGLE_CLASSROOM_REQUIRED(HttpStatus.BAD_REQUEST, "팀 계층은 반을 하나만 지정해야 합니다."),

	// ── 기수 간 비교 ──

	/** 비교 대상이 같은 기관의 다른 기수가 아니다. 화면은 드롭다운 후보 목록을 다시 읽어야 한다. */
	BASELINE_COHORT_INVALID(HttpStatus.BAD_REQUEST, "같은 기관의 다른 기수만 비교 대상으로 지정할 수 있습니다."),

	// ── 반별 진행 현황 ──

	/** 회차 번호가 1 미만이다. */
	ROUND_NO_INVALID(HttpStatus.BAD_REQUEST, "회차 번호가 올바르지 않습니다."),
	/** 그 프로젝트에 그 번호의 회차가 없다. round_no는 프로젝트 안에서만 유일하다. */
	PROJECT_ROUND_NOT_FOUND(HttpStatus.NOT_FOUND, "프로젝트 회차를 찾을 수 없습니다."),
	/** 다른 기관의 프로젝트다. */
	PROJECT_CROSS_ORGANIZATION(HttpStatus.FORBIDDEN, "다른 기관의 프로젝트는 조회할 수 없습니다."),

	HEATMAP_SCOPE_INVALID(HttpStatus.BAD_REQUEST, "히트맵 계층에 필요한 반·팀 필터가 올바르지 않습니다."),
	HEATMAP_REVIEW_TRAINEE_REQUIRED(HttpStatus.BAD_REQUEST, "다시 보기 비교는 개인 계층에서만 조회할 수 있습니다."),
	CONCEPT_SCOPE_NOT_FOUND(HttpStatus.NOT_FOUND, "담당 범위에서 개념 소관을 계산할 수 없습니다.");


	private final HttpStatus status;
	private final String defaultMessage;

	AnalyticsErrorCode(HttpStatus status, String defaultMessage) {
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
