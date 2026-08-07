package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.global.exception.ApiException;

/**
 * 이미 등록됐거나 초대 중인 이메일이라 초대를 만들 수 없다.
 *
 * <p>{@link ApiException}을 상속하므로 아무도 잡지 않으면 {@code ALREADY_INVITED} 코드가 그대로
 * 응답에 실린다. 전에는 {@code GlobalExceptionHandler}에 이 타입만을 위한 핸들러가 있었고
 * 코드 문자열이 거기 리터럴로 박혀 있었다 — 코드가 예외 옆에 있지 않으니 문서 생성기가 찾지 못했다.
 *
 * <p><b>전용 타입을 남기는 이유</b>는 organization·platformgovernance가 이 예외를 <b>잡아서</b>
 * 자기 도메인 코드로 바꿔 던지기 때문이다(목업 계약이 오퍼레이터 탭 기준이라 코드 이름이 다르다).
 * 그 catch 절이 성립하려면 구체 타입이 있어야 한다.
 */
public class InvitationConflictException extends ApiException {

	public InvitationConflictException(String message) {
		super(MemberErrorCode.ALREADY_INVITED, message);
	}

	public InvitationConflictException(String message, Throwable cause) {
		super(MemberErrorCode.ALREADY_INVITED, message, cause);
	}
}
