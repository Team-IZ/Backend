package com.bigproject.backend.global.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * 데이터 보존기간이 {@link RetentionPolicy#ALLOWED_DAYS} 안의 값인지 검증한다.
 *
 * <p><b>enum 으로 바꾸지 않은 이유</b> — DTO 타입을 enum 으로 올리면 JSON 와이어 포맷이 바뀐다.
 * 프론트는 {@code "dataRetentionDays": 180} 처럼 <b>숫자</b>로 보내는데 enum 은 기본적으로 문자열을
 * 기대하므로 역직렬화가 깨진다. {@code int} 를 유지하고 검증만 붙인다.
 */
@Documented
@Target({FIELD, PARAMETER, ANNOTATION_TYPE, RECORD_COMPONENT})
@Retention(RUNTIME)
@Constraint(validatedBy = AllowedRetentionDaysValidator.class)
public @interface AllowedRetentionDays {

	String message() default "데이터 보존기간은 90·180·365일 중에서만 선택할 수 있습니다.";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
