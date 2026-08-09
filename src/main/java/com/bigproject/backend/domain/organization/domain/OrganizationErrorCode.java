package com.bigproject.backend.domain.organization.domain;

import com.bigproject.backend.global.exception.ApiErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 목업 케이스 계약 표(SA-01 {@code #cases-sa01} / SA-02 {@code #cases-sa02})가 지정한 에러 코드.
 *
 * <p>프론트가 코드로 분기하고 문구를 화면에 맞춰 쓰기 때문에, 메시지가 아니라 <b>코드</b>가 계약이다.
 * 여기 없는 코드를 임의로 만들지 말고 목업 표를 먼저 갱신한다.
 *
 * <p>member 도메인에서 올라오는 초대 관련 실패({@link #ALREADY_INVITED}, {@link #INVITE_MAIL_FAILED})는
 * organization 도메인이 잡아서 이 코드로 다시 던진다 — 목업 계약이 오퍼레이터 탭 기준이기 때문이다.
 */
public enum OrganizationErrorCode implements ApiErrorCode {

	// ── SA-01 기관 목록 ──
	/** case 1 · 중복 기관명. 제출 전 실시간 확인으로 막지만 최종 방어도 필요하다. */
	ORG_NAME_TAKEN(HttpStatus.CONFLICT, "이미 있는 기관명입니다."),
	/** case 3 · 생성 실패. 아무것도 만들어지지 않는다(입력값은 화면이 유지한다). */
	ORG_CREATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "기관을 만들지 못했습니다."),
	/**
	 * 같은 Idempotency-Key로 <b>다른 내용</b>의 요청이 들어왔다. 같은 키·같은 내용이면 최초 결과를 그대로 돌려주지만,
	 * 내용이 다르면 어느 쪽을 만들어야 할지 알 수 없으므로 거절한다(v06 organization.create_idempotency_key).
	 */
	ORG_IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "같은 요청 키로 다른 내용이 이미 처리되었습니다."),

	// ── 공통 ──
	ORG_NOT_FOUND(HttpStatus.NOT_FOUND, "기관을 찾을 수 없습니다."),
	ORG_ALREADY_DELETED(HttpStatus.CONFLICT, "이미 삭제된 기관입니다."),
	ORG_NOT_DELETED(HttpStatus.CONFLICT, "삭제되지 않은 기관입니다."),
	ORG_STATUS_NOT_MUTABLE(HttpStatus.BAD_REQUEST, "기관 운영 상태는 활성 또는 정지만 직접 설정할 수 있습니다."),
	ORG_POLICY_NOT_FOUND(HttpStatus.CONFLICT, "기관에 활성 운영 정책이 없습니다."),
	ORG_ACCESS_DENIED(HttpStatus.FORBIDDEN, "다른 기관의 정보는 조회할 수 없습니다."),

	// ── SA-02 기관 삭제 ──
	/** case 7 · 확인 모달에서 기관명을 직접 입력해야 삭제된다. */
	ORG_DELETE_CONFIRM_MISMATCH(HttpStatus.BAD_REQUEST, "기관명이 일치하지 않습니다."),
	/** case 8 · 보존기간이 남아 파기할 수 없다. 즉시 물리 삭제 경로를 두지 않는다. */
	RETENTION_NOT_MET(HttpStatus.CONFLICT, "보존기간이 남아 파기할 수 없습니다."),

	// ── SA-02 오퍼레이터 ──
	/** case N2 · 마지막 오퍼레이터를 정지하면 기관에 들어갈 수 있는 사람이 없어진다(고아 기관 방지). */
	LAST_OPERATOR(HttpStatus.CONFLICT, "이 기관의 마지막 오퍼레이터입니다."),
	OPERATOR_NOT_FOUND(HttpStatus.NOT_FOUND, "이 기관의 오퍼레이터 계정을 찾을 수 없습니다."),
	OPERATOR_INVITATION_NOT_FOUND(HttpStatus.NOT_FOUND, "취소할 수 있는 오퍼레이터 초대를 찾을 수 없습니다."),
	/** case 2·N1 · 기관 도메인 밖 주소로는 초대할 수 없다. */
	DOMAIN_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "기관 도메인 주소로만 초대할 수 있습니다."),
	ALREADY_INVITED(HttpStatus.CONFLICT, "이미 등록되었거나 초대된 이메일입니다."),
	/** case 4·5 · 메일/토큰 실패는 서버 사정이 다를 뿐 사용자가 할 일은 재발송 하나라 한 코드로 합친다. */
	INVITE_MAIL_FAILED(HttpStatus.BAD_GATEWAY, "지정됐지만 초대 메일이 나가지 않았습니다."),

	// ── SA-02 사용량 ──
	/** case 6 · 집계를 못 읽었을 때. 0으로 그리면 청구액이 실제보다 작아 보인다. */
	USAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "사용량을 불러오지 못했습니다."),

	// ── SA-03 플랫폼 설정 ──
	/** 채점 모델 정책이 아직 없다. 플랫폼 초기 설정이 끝나지 않은 상태다. */
	GRADING_POLICY_NOT_FOUND(HttpStatus.CONFLICT, "활성 채점 모델 정책이 없습니다."),
	/** 존재하지 않거나 비활성(INACTIVE) 모델을 정책·티어에 지정하려 한 경우. */
	AI_MODEL_NOT_AVAILABLE(HttpStatus.BAD_REQUEST, "사용할 수 없는 AI 모델입니다."),
	/** 이미 사용 중인 캘리브레이션 버전 코드. version_code는 전체 UNIQUE다. */
	CALIBRATION_VERSION_CODE_TAKEN(HttpStatus.CONFLICT, "이미 사용 중인 캘리브레이션 버전 코드입니다."),
	/**
	 * 재캘리브레이션이 이미 진행 중이다. 겹쳐 실행하면 어느 버전이 결과 비교 기준인지 알 수 없어진다 —
	 * 진행 중 버전이 끝나거나 실패로 정리된 뒤에 다시 시도해야 한다.
	 */
	CALIBRATION_IN_PROGRESS(HttpStatus.CONFLICT, "재캘리브레이션이 이미 진행 중입니다."),
	CALIBRATION_VERSION_NOT_FOUND(HttpStatus.NOT_FOUND, "캘리브레이션 버전을 찾을 수 없습니다."),
	/** 티어 매핑을 찾을 수 없다(기능·티어 조합이 아직 설정되지 않음). */
	TIER_POLICY_NOT_FOUND(HttpStatus.NOT_FOUND, "티어 모델 매핑을 찾을 수 없습니다."),
	SUPER_ADMIN_NOT_FOUND(HttpStatus.NOT_FOUND, "슈퍼어드민 계정을 찾을 수 없습니다."),
	/**
	 * 마지막 활성 슈퍼어드민은 정지할 수 없다. SA-02의 {@link #LAST_OPERATOR}와 같은 이유이며,
	 * 이쪽은 풀어 줄 상위 권한이 아예 없어서 더 치명적이다.
	 */
	LAST_SUPER_ADMIN(HttpStatus.CONFLICT, "이 플랫폼의 마지막 슈퍼어드민입니다.");

	private final HttpStatus status;
	private final String defaultMessage;

	OrganizationErrorCode(HttpStatus status, String defaultMessage) {
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
