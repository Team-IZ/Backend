package com.bigproject.backend.global.exception;

/**
 * 도메인 에러 코드 enum이 구현하는 공통 계약.
 *
 * <p>이걸 구현해야 OpenAPI 문서 생성기({@code SwaggerConfig})가 코드 목록과 기본 메시지를 읽어
 * <b>각 4xx·5xx 응답의 examples 키로 실제 에러 코드를 실어 준다.</b> 프론트는 그 키에서 코드 상수를
 * 기계적으로 뽑는다 — 설명 문자열을 사람이 읽고 손으로 옮겨 적지 않게 하는 것이 목적이다.
 *
 * <p>새 도메인이 에러 코드 enum을 만들면 이 인터페이스를 구현하고
 * {@code SwaggerConfig}의 카탈로그에 등록한다.
 */
public interface ApiErrorCode {

	/** enum 상수 이름. 프론트가 분기하는 계약값이며 {@link ErrorResponse#code()}에 그대로 실린다. */
	String name();

	/** 사람이 읽는 기본 메시지. 화면 문구는 프론트가 정하므로 로그·폴백·문서 예시용이다. */
	String defaultMessage();
}
