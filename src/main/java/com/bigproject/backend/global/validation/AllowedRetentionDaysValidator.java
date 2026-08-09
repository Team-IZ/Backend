package com.bigproject.backend.global.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class AllowedRetentionDaysValidator implements ConstraintValidator<AllowedRetentionDays, Integer> {

	@Override
	public boolean isValid(Integer value, ConstraintValidatorContext context) {
		// null 여부는 @NotNull이 판단한다. 여기서 false를 주면 어느 제약이 깨졌는지 메시지가 뒤섞인다.
		return value == null || RetentionPolicy.isAllowed(value);
	}
}
