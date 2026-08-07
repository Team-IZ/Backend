package com.bigproject.backend.domain.auth.domain;

import com.bigproject.backend.global.exception.ApiErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 비밀번호 재설정 흐름이 내려보내는 에러 코드.
 *
 * <p>전에는 코드 문자열이 {@code PasswordResetService} 안에 리터럴로 흩어져 있었다 —
 * 프론트는 코드로 화면 문구를 갈라 쓰는데(재설정 7케이스) 문자열이 코드 안에만 있으면
 * OpenAPI 문서에 코드 목록을 실을 수 없어 프론트가 손으로 다시 적게 된다.
 * {@link ApiErrorCode}를 구현해 두면 문서 생성기가 여기서 코드와 기본 메시지를 읽어 간다.
 */
public enum AuthErrorCode implements ApiErrorCode {

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

	public HttpStatus status() {
		return status;
	}

	@Override
	public String defaultMessage() {
		return defaultMessage;
	}
}
