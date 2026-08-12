package com.bigproject.backend.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	/** 안정 코드가 없는 예외에 붙이는 검증 실패 코드. 프론트는 fieldErrors를 읽어 입력칸에 붙인다. */
	static final String VALIDATION_FAILED = "VALIDATION_FAILED";

	/**
	 * 본문 DTO의 Bean Validation 실패.
	 *
	 * <p>전에는 "요청 값이 올바르지 않습니다." 한 줄만 내려서 <b>어느 칸이 틀렸는지 알 수 없었다.</b>
	 * 화면이 입력칸 옆에 사유를 붙일 수 있도록 필드명·사유를 {@code fieldErrors}로 함께 준다.
	 * 값 자체는 싣지 않는다 — 비밀번호처럼 되돌려 보내면 안 되는 입력이 섞여 있다.
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(
			MethodArgumentNotValidException exception,
			HttpServletRequest request
	) {
		List<ErrorResponse.FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
				.map(error -> ErrorResponse.FieldError.of(
						error.getField(),
						// getCode()가 제약 이름(NotBlank·Size)을 준다. 기본 문구는 영문이라 계약이 못 되므로
						// 프론트가 분기할 값은 이쪽이다.
						error.getCode(),
						error.getDefaultMessage() == null ? "올바르지 않은 값입니다." : error.getDefaultMessage()
				))
				.toList();

		return ResponseEntity.badRequest().body(ErrorResponse.of(
				HttpStatus.BAD_REQUEST,
				VALIDATION_FAILED,
				"요청 값이 올바르지 않습니다.",
				fieldErrors
		));
	}

	/**
	 * 본문 JSON을 DTO로 만들지 못했다. 형식이 깨졌거나, 허용값이 아닌 enum 문자열이 왔거나,
	 * 필드 타입이 맞지 않는 경우다.
	 *
	 * <p>잡지 않으면 스프링 기본 처리로 넘어가 <b>이 API만 응답 모양이 달라진다</b> — 다른 400은
	 * {@code code}·{@code fieldErrors}를 담은 ErrorResponse인데 여기만 {timestamp, status, error, path}가
	 * 나가서 화면이 분기해야 한다. 허용값을 타입으로 좁힌 DTO(예: {@code TraineeStatusUpdate})는
	 * 잘못된 값이 이 경로로 오므로 형식을 맞춰 둔다.
	 *
	 * <p>파싱 실패 원문은 싣지 않는다. 역직렬화 예외 메시지에는 클래스 경로와 입력값이 그대로 들어 있다.
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleUnreadableBody(
			HttpMessageNotReadableException exception,
			HttpServletRequest request
	) {
		log.warn("요청 본문을 읽지 못했습니다: method={}, path={}",
				request.getMethod(), request.getRequestURI(), exception);
		return ResponseEntity.badRequest().body(ErrorResponse.of(
				HttpStatus.BAD_REQUEST,
				VALIDATION_FAILED,
				"요청 본문을 읽을 수 없습니다. 형식과 허용값을 확인해 주세요.",
				List.of()
		));
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
		List<ErrorResponse.FieldError> fieldErrors = exception.getConstraintViolations().stream()
				.map(violation -> ErrorResponse.FieldError.of(
						violation.getPropertyPath().toString(),
						violation.getConstraintDescriptor() == null ? null
								: violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(),
						violation.getMessage()
				))
				.toList();

		return ResponseEntity.badRequest().body(ErrorResponse.of(
				HttpStatus.BAD_REQUEST,
				VALIDATION_FAILED,
				"요청 값이 올바르지 않습니다.",
				fieldErrors
		));
	}

	/**
	 * DB 제약 위반(CHECK·NOT NULL·UNIQUE 등).
	 *
	 * <p>잡지 않으면 Spring 기본 처리로 넘어가 <b>실행 SQL과 실패한 행 전체가 응답에 실린다.</b>
	 * 실제로 초대 취소 실패 시 사용자 이메일·초대 토큰 해시·org_id가 그대로 노출된 적이 있다.
	 *
	 * <p>원인은 <b>서버 로그에만</b> 남기고 클라이언트에는 상태와 짧은 문구만 준다.
	 * 어떤 제약이 왜 깨졌는지는 외부에 알려 줄 정보가 아니다.
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
			DataIntegrityViolationException exception,
			HttpServletRequest request
	) {
		log.error("DB 제약 위반: method={}, path={}", request.getMethod(), request.getRequestURI(), exception);
		return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
				HttpStatus.CONFLICT.value(),
				"DATA_INTEGRITY_VIOLATION",
				"DATA_INTEGRITY_VIOLATION",
				"요청을 처리할 수 없습니다. 데이터 제약 조건에 맞지 않습니다."
		));
	}

	/**
	 * 매핑되지 않은 경로. <b>상태는 그대로 404이고 응답 봉투만 공통 형식으로 맞춘다.</b>
	 *
	 * <p>잡지 않으면 스프링 기본 오류 본문({@code timestamp·status·error·message·path})이 나가는데,
	 * 거기에는 {@code code}가 없다. 프론트는 스펙에서 {@code ErrorResponse}로 에러 타입을 생성해
	 * {@code code}로 분기하므로, <b>이 경로만 타입이 어긋나 분기가 실패한다.</b>
	 *
	 * <p>유효한 토큰으로 없는 경로를 부르면 이미 404다(401이 아니다 — 실측 확인). 그래서 여기서
	 * 바꾸는 것은 상태가 아니라 모양뿐이며, "토큰 만료"와 "경로 오타"의 구분은 그대로 유지된다.
	 *
	 * <p>경로는 응답에 싣지 않는다. 요청자가 이미 아는 값이고, 존재하지 않는 경로를 되돌려 주면
	 * 반사형 노출의 통로가 된다.
	 */
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleNoResourceFound(
			NoResourceFoundException exception,
			HttpServletRequest request
	) {
		log.info("매핑되지 않은 경로: method={}, path={}", request.getMethod(), request.getRequestURI());
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(
				HttpStatus.NOT_FOUND,
				"NOT_FOUND",
				"요청한 경로를 찾을 수 없습니다."
		));
	}

	/*
	 * @ExceptionHandler(Exception.class) 같은 catch-all은 두지 않는다.
	 *
	 * 스프링이 상태 코드를 담아 던지는 자체 예외(NoResourceFoundException 404,
	 * HttpRequestMethodNotSupportedException 405 등)까지 가로채 전부 500으로 만들어 버린다.
	 * 실제로 넣었다가 "없는 경로 → 404" 가 500이 되는 회귀가 났다.
	 *
	 * 처리되지 않은 예외의 내부 정보 노출은 application.yaml 의
	 * server.error.include-stacktrace=never 로 막는다.
	 */

	/**
	 * {@code ResponseStatusException}은 안정 코드를 담지 않는다(auth 도메인이 주로 쓴다).
	 * 프론트가 최소한 상태 단위로는 분기할 수 있도록 <b>HTTP 상태 이름</b>을 코드로 쓴다 —
	 * 케이스별 분기가 필요해지면 그 지점을 도메인 예외로 승격시켜 코드를 붙인다.
	 */
	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ErrorResponse> handleResponseStatus(
			ResponseStatusException exception,
			HttpServletRequest request
	) {
		HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
		ErrorResponse response = ErrorResponse.of(
				exception.getStatusCode().value(),
				exception.getStatusCode().toString(),
				status == null ? "UNEXPECTED_ERROR" : status.name(),
				exception.getReason() == null ? "요청을 처리할 수 없습니다." : exception.getReason()
		);
		return ResponseEntity.status(exception.getStatusCode()).body(response);
	}
}
