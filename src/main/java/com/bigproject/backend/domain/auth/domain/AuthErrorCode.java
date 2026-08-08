package com.bigproject.backend.domain.auth.domain;

import com.bigproject.backend.global.exception.ApiErrorCode;
import org.springframework.http.HttpStatus;

/**
 * auth 도메인이 내려보내는 에러 코드.
 *
 * <p>전에는 코드 문자열이 {@code PasswordResetService} 안에 리터럴로 흩어져 있었다 —
 * 프론트는 코드로 화면 문구를 갈라 쓰는데(재설정 7케이스) 문자열이 코드 안에만 있으면
 * OpenAPI 문서에 코드 목록을 실을 수 없어 프론트가 손으로 다시 적게 된다.
 * {@link ApiErrorCode}를 구현해 두면 문서 생성기가 여기서 코드와 기본 메시지를 읽어 간다.
 *
 * <p><b>왜 상태 코드만으로는 부족한가.</b> 로그인 실패 네 가지(비밀번호 불일치 · 정지 계정 ·
 * 기관 정지 · 일시 차단)가 전부 같은 {@code 403 FORBIDDEN}으로 나가던 시절, 클라이언트가
 * 쓸 수 있는 단서는 {@code message} 문자열뿐이었다. 그 문자열은 계약이 아니라서
 * 문구를 다듬는 순간 화면 분기가 조용히 깨진다. 코드가 그 자리를 대신한다.
 */
public enum AuthErrorCode implements ApiErrorCode {

	// ── 로그인 ──

	/**
	 * 이메일이 없거나 비밀번호가 틀렸다. <b>둘을 구분해 주지 않는다</b> — 구분하면 어떤 이메일이
	 * 가입돼 있는지 외부에서 확인할 수 있다(계정 열거).
	 *
	 * <p>아직 활성화하지 않은 계정(status=PENDING)도 여기로 온다. 그 계정은 비밀번호가
	 * 아예 없어서 아래 상태 검사에 도달하지 못하기 때문이다. 자세한 사정은
	 * {@link #LOGIN_ACCOUNT_INACTIVE} 주석 참고.
	 */
	LOGIN_INVALID(HttpStatus.BAD_REQUEST, "이메일 또는 비밀번호가 올바르지 않습니다."),

	/**
	 * 정지·퇴사 처리된 계정이다(status=INACTIVE). 화면은 문의 안내만 띄우고 재시도를 권하지 않는다.
	 *
	 * <p><b>계정 열거로 이어지지 않는다.</b> 이 코드는 비밀번호가 맞은 다음에만 나간다 —
	 * 비밀번호를 아는 사람에게 "이 계정은 정지됐다"고 알려 주는 것뿐이라 새로 새는 정보가 없다.
	 * 반대로 비밀번호 검사 <b>앞에서</b> 상태를 보면 아무나 이메일 존재 여부를 확인할 수 있게 된다.
	 */
	LOGIN_ACCOUNT_INACTIVE(HttpStatus.FORBIDDEN, "정지된 계정입니다. 관리자에게 문의해 주세요."),

	/**
	 * 계정은 멀쩡한데 소속 기관이 정지 상태다. 사용자가 할 수 있는 일이 없으므로
	 * {@link #LOGIN_ACCOUNT_INACTIVE}와 다른 문구가 나가야 한다.
	 */
	LOGIN_ORG_SUSPENDED(HttpStatus.FORBIDDEN, "소속 기관이 정지되어 로그인할 수 없습니다."),

	/**
	 * 연속 실패로 일시 차단된 상태다. 화면은 남은 시간을 세어 보여 주므로 응답에
	 * {@code retryAfter}(초)와 {@code Retry-After} 헤더를 함께 싣는다.
	 *
	 * <p>내는 곳이 둘이다 — 이메일+IP로 연속 실패를 세는
	 * {@link com.bigproject.backend.domain.auth.application.LoginAttemptThrottle}(5회 → 60초, 이후
	 * 실패마다 2배, 상한 15분)와, 운영자가 계정 단위로 직접 채우는
	 * {@code app_user.login_blocked_until}이다. 화면이 할 일은 둘 다 같으므로 코드는 하나다.
	 */
	LOGIN_TEMPORARILY_BLOCKED(HttpStatus.TOO_MANY_REQUESTS, "로그인 시도가 많아 잠시 차단되었습니다."),

	/**
	 * SUPER_ADMIN이 아닌데 소속 기관이 없다. 사용자 잘못이 아니라 <b>계정 데이터 결함</b>이라
	 * 4xx가 아니라 5xx다 — 같은 입력으로 다시 시도해도 결과가 같으므로 화면은 재시도 대신
	 * 관리자 문의를 안내한다.
	 */
	LOGIN_NO_ORG_CONTEXT(HttpStatus.INTERNAL_SERVER_ERROR, "기관 인증 컨텍스트를 발급할 수 없습니다."),

	/** 허용되지 않은 Origin에서 온 요청이다. 계정 문제와 구분돼야 화면이 엉뚱한 안내를 하지 않는다. */
	LOGIN_ORIGIN_NOT_ALLOWED(HttpStatus.FORBIDDEN, "허용되지 않은 요청 출처입니다."),

	// ── 토큰 재발급 ──

	/** 리프레시 토큰이 없거나 만료·위조됐다. 화면은 조용히 로그인 화면으로 보낸다. */
	REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "유효한 리프레시 토큰이 필요합니다."),
	/**
	 * 토큰은 유효하지만 담긴 역할·기관이 현재 계정과 다르다(권한 변경·기관 이동).
	 * {@link #REFRESH_TOKEN_INVALID}와 달리 <b>재로그인해야 한다</b>는 안내가 필요하다.
	 */
	REFRESH_IDENTITY_CHANGED(HttpStatus.UNAUTHORIZED, "인증 정보가 변경되어 다시 로그인해야 합니다."),

	// ── 초대 해석·활성화 ──

	/** 초대 토큰이 없거나 위변조·교체됐거나 초대 상태가 SENT가 아니다. */
	INVITATION_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 초대 링크입니다."),
	/** 초대 링크가 만료됐다. 화면은 재발송 안내를 띄운다. */
	INVITATION_EXPIRED(HttpStatus.GONE, "초대 링크가 만료되었습니다."),
	/** 이미 수락된 초대다. 만료와 달리 재발송이 아니라 로그인으로 안내해야 한다. */
	INVITATION_ALREADY_ACCEPTED(HttpStatus.CONFLICT, "이미 수락된 초대입니다."),
	/** 비밀번호와 확인 값이 다르다. */
	PASSWORD_CONFIRMATION_MISMATCH(HttpStatus.BAD_REQUEST, "비밀번호 확인이 일치하지 않습니다."),
	/** 필수 동의 항목이 빠졌다. */
	REQUIRED_CONSENT_MISSING(HttpStatus.BAD_REQUEST, "필수 동의 항목에 모두 동의해야 합니다."),
	/**
	 * 동시 요청으로 계정·초대 상태가 먼저 바뀌었다. 화면은 새로고침 후 재시도를 안내한다 —
	 * 입력이 틀린 것이 아니므로 입력칸에 오류를 붙이면 안 된다.
	 */
	ACTIVATION_STATE_CHANGED(HttpStatus.CONFLICT, "계정 활성화 상태가 변경되었습니다. 다시 시도해 주세요."),

	// ── 비밀번호 재설정 ──

	/** 토큰이 없거나 위변조됐거나 계정이 활성 상태가 아니다. 어느 쪽인지 구분해 주지 않는다. */
	RESET_TOKEN_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 비밀번호 재설정 링크입니다."),
	RESET_TOKEN_USED(HttpStatus.CONFLICT, "이미 사용된 비밀번호 재설정 링크입니다."),
	/** 화면은 이 코드에서 재발송 안내를 띄운다. */
	RESET_TOKEN_EXPIRED(HttpStatus.GONE, "비밀번호 재설정 링크가 만료되었습니다."),
	WEAK_PASSWORD(HttpStatus.UNPROCESSABLE_CONTENT, "비밀번호는 8~64자이며 영문, 숫자, 특수문자를 포함해야 합니다."),
	SAME_AS_CURRENT(HttpStatus.UNPROCESSABLE_CONTENT, "지금 쓰는 비밀번호와 달라야 합니다."),
	/** 저장 단계에서 실패했다. 비밀번호는 바뀌지 않았으므로 화면은 재시도를 안내한다. */
	RESET_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "비밀번호는 아직 바뀌지 않았습니다. 잠시 후 다시 시도해 주세요.");

	private final HttpStatus status;
	private final String defaultMessage;

	AuthErrorCode(HttpStatus status, String defaultMessage) {
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
