package com.bigproject.backend.domain.member.domain;

import com.bigproject.backend.global.exception.ApiErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 초대·명단 등록 흐름이 내려보내는 에러 코드.
 *
 * <p>명단 등록은 실패 사유가 여럿인데 전부 {@code 400 BAD_REQUEST}로 나가고 있었다.
 * 화면이 해야 할 일은 사유마다 다르다 — CSV 형식이 틀리면 파일을 고쳐 다시 올리게 하고,
 * 중복 이메일이면 그 행만 빼게 하고, 권한 문제면 애초에 버튼을 보여주지 말아야 한다.
 *
 * <p>CSV 형식 오류는 코드를 하나로 묶고({@link #CSV_FORMAT_INVALID}) 어느 행이 왜 틀렸는지는
 * {@code message}에 담는다. 화면이 하는 일이 "파일을 고쳐 다시 올리세요" 하나로 같아서,
 * 사유마다 코드를 만들면 프론트가 열한 갈래를 똑같이 처리하는 분기를 짜게 된다.
 */
public enum MemberErrorCode implements ApiErrorCode {

	/** 이미 등록됐거나 초대 중인 이메일. 목업 오퍼레이터 탭 계약과 같은 이름을 쓴다. */
	ALREADY_INVITED(HttpStatus.CONFLICT, "이미 등록되었거나 초대된 이메일입니다."),
	/** 이메일 주소 형식이 아니다. */
	EMAIL_FORMAT_INVALID(HttpStatus.BAD_REQUEST, "이메일 형식이 올바르지 않습니다."),
	/**
	 * CSV 파일 자체를 읽을 수 없다(인코딩·헤더·따옴표·행 형식·건수 초과 등).
	 * 구체적인 사유와 행 번호는 {@code message}에 있다.
	 */
	CSV_FORMAT_INVALID(HttpStatus.BAD_REQUEST, "CSV 파일을 읽을 수 없습니다."),
	/** CSV 행의 이름 칸이 비었거나 너무 길다. */
	TRAINEE_NAME_INVALID(HttpStatus.BAD_REQUEST, "교육생 이름이 올바르지 않습니다."),

	/** 초대 메일을 보내지 못했다. 계정은 만들어지지 않았으므로 화면은 재시도를 안내한다. */
	INVITE_MAIL_FAILED(HttpStatus.BAD_GATEWAY, "초대 메일을 보내지 못했습니다."),
	/** 초대 정보를 저장하지 못했다. 메일 발송 실패와 달리 서버 결함이다. */
	INVITATION_SAVE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "초대 정보를 저장할 수 없습니다."),
	/** 재발송할 수 있는 초대가 없다. 이미 수락됐거나 취소된 경우다. */
	INVITATION_NOT_RESENDABLE(HttpStatus.NOT_FOUND, "재발송할 수 있는 초대를 찾을 수 없습니다."),

	/** 초대할 권한이 없는 역할이다(슈퍼어드민만 오퍼레이터를, 오퍼레이터만 매니저·교육생을 초대한다). */
	INVITE_ROLE_NOT_ALLOWED(HttpStatus.FORBIDDEN, "이 역할을 초대할 권한이 없습니다."),
	/** 다른 기관의 대상에는 초대를 보낼 수 없다. */
	INVITE_CROSS_ORGANIZATION(HttpStatus.FORBIDDEN, "다른 기관에는 초대할 수 없습니다."),
	/** 초대하는 사람 자신이 활성 계정이 아니다. */
	INVITER_NOT_ACTIVE(HttpStatus.FORBIDDEN, "활성 사용자만 초대할 수 있습니다."),

	/** 초대 대상 기관을 찾을 수 없거나 활성 상태가 아니다. */
	ORGANIZATION_NOT_FOUND(HttpStatus.NOT_FOUND, "활성 기관을 찾을 수 없습니다."),
	/** 초대 가능한 기수가 없다. */
	COHORT_NOT_INVITABLE(HttpStatus.NOT_FOUND, "초대 가능한 기수를 찾을 수 없습니다."),
	/** 지정한 기수가 이 기관 소속이 아니다. */
	COHORT_NOT_IN_ORGANIZATION(HttpStatus.BAD_REQUEST, "기관에 속하지 않은 기수입니다."),
	/** 일반 매니저는 담당 기수를 정확히 하나 지정해야 한다. */
	MANAGER_COHORT_REQUIRED(HttpStatus.BAD_REQUEST, "일반 매니저는 하나의 기수를 반드시 지정해야 합니다."),

	/** 인증 사용자를 찾을 수 없다. 토큰은 유효한데 계정이 사라진 경우다. */
	INVITER_NOT_FOUND(HttpStatus.UNAUTHORIZED, "인증 사용자를 찾을 수 없습니다.");

	private final HttpStatus status;
	private final String defaultMessage;

	MemberErrorCode(HttpStatus status, String defaultMessage) {
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
