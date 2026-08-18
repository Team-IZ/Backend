package com.bigproject.backend.domain.auth.presentation.dto;

import com.bigproject.backend.domain.auth.application.ConsentDocumentCatalog;
import com.bigproject.backend.domain.auth.domain.ConsentCatalog;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "가입 화면에 표시할 동의 항목")
public record ConsentItemResponse(
		@Schema(description = "동의 항목 코드", example = "TERMS_OF_SERVICE")
		String code,
		@Schema(description = "화면에 표시할 항목명", example = "서비스 이용약관")
		String title,
		@Schema(description = "체크박스 옆에 붙이는 한 줄 요약이며 약관 본문이 아니다")
		String description,
		@Schema(description = """
				동의 문서 본문(Markdown). 화면은 이 값을 `전문 보기`에 그대로 렌더링한다.
				한 줄 요약(description)만 보여 주고 동의를 받으면 이용자가 무엇에 동의했는지 알 수 없다.""")
		String body,
		@Schema(
				description = """
						본문의 SHA-256(hex 64자). 이 값이 동의 기록의 `evidence_hash` 재료로 들어가
						"이 사용자가 어떤 문안에 동의했는가"를 나중에 증명할 수 있게 한다.
						문안에서 한 글자만 바뀌어도 값이 달라진다.""",
				example = "3b1f0c9d2a4e6b8d0f2a4c6e8b0d2f4a6c8e0b2d4f6a8c0e2b4d6f8a0c2e4b6d")
		String documentHash,
		@Schema(description = "가입 요청에서 이 항목의 동의 여부를 담을 필드명", example = "serviceTermsAgreed")
		String requestField,
		@Schema(description = "필수 동의 여부이며 true인 항목은 false로 제출하면 가입이 400으로 거부된다")
		boolean required,
		@Schema(description = "표시한 동의 문서 버전이며 가입 요청과 동의 기록에 그대로 쓰인다", example = "1")
		int policyVersion
) {
	public static ConsentItemResponse from(
			ConsentCatalog.ConsentItem item,
			ConsentDocumentCatalog.ConsentDocument document,
			int policyVersion
	) {
		return new ConsentItemResponse(
				item.code().name(),
				item.title(),
				item.description(),
				document.body(),
				document.documentHash(),
				item.requestField(),
				item.required(),
				policyVersion
		);
	}
}
