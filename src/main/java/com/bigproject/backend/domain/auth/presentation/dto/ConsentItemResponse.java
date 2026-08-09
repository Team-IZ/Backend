package com.bigproject.backend.domain.auth.presentation.dto;

import com.bigproject.backend.domain.auth.domain.ConsentCatalog;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "가입 화면에 표시할 동의 항목")
public record ConsentItemResponse(
		@Schema(description = "동의 항목 코드", example = "TERMS_OF_SERVICE")
		String code,
		@Schema(description = "화면에 표시할 항목명", example = "서비스 이용약관")
		String title,
		@Schema(description = "항목 설명 문구")
		String description,
		@Schema(description = "가입 요청에서 이 항목의 동의 여부를 담을 필드명", example = "serviceTermsAgreed")
		String requestField,
		@Schema(description = "필수 동의 여부이며 true인 항목은 false로 제출하면 가입이 400으로 거부된다")
		boolean required,
		@Schema(description = "표시한 동의 문서 버전이며 가입 요청과 동의 기록에 그대로 쓰인다", example = "1")
		int policyVersion
) {
	public static ConsentItemResponse from(ConsentCatalog.ConsentItem item, int policyVersion) {
		return new ConsentItemResponse(
				item.code().name(),
				item.title(),
				item.description(),
				item.requestField(),
				item.required(),
				policyVersion
		);
	}
}
