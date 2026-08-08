package com.bigproject.backend.domain.usagemetering.presentation.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부분 수정 요청이 <b>본문에서 세 가지 상태를 읽어 내는지</b> 고정한다.
 *
 * <p>보통의 필드로는 "키가 없음"과 "null을 보냄"이 구분되지 않는다 — Jackson이 양쪽 다 {@code null}로
 * 넘긴다. 상한 두 개는 {@code null}이 <b>무제한</b>이라는 값이라 그 구분이 곧 기능이다.
 * 구분이 깨지면 한 번 상한을 건 기관을 다시 무제한으로 되돌릴 수 없게 된다.
 */
class UpdateOperationSettingRequestTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	void 키가_없으면_상한_필드가_비어_있다() {
		UpdateOperationSettingRequest request = read("{\"allowDataExport\": true}");

		// null = "안 보냈다". 서비스는 이때 직전 버전의 상한을 그대로 승계한다.
		assertThat(request.monthlyTokenLimit()).isNull();
		assertThat(request.storageLimitBytes()).isNull();
	}

	@Test
	void null을_보내면_무제한_의도가_남는다() {
		UpdateOperationSettingRequest request = read("{\"monthlyTokenLimit\": null}");

		assertThat(request.monthlyTokenLimit()).isNotNull();
		assertThat(request.monthlyTokenLimit().value()).isNull();
	}

	@Test
	void 값을_보내면_그_값이_담긴다() {
		UpdateOperationSettingRequest request = read("{\"monthlyTokenLimit\": 100}");

		assertThat(request.monthlyTokenLimit().value()).isEqualTo(100L);
	}

	@Test
	void 빈_본문은_400이다() {
		// 바뀌는 값 없이 정책 버전만 올라가는 요청을 막는다.
		assertThat(violationsOf(read("{}"))).contains("변경할 항목을 하나 이상 보내야 합니다.");
	}

	@Test
	void 필드가_하나라도_있으면_통과한다() {
		assertThat(violationsOf(read("{\"allowZipSubmission\": false}"))).isEmpty();
	}

	@Test
	void 무제한으로_푸는_요청은_상한_검사에_걸리지_않는다() {
		// null은 "0 이하"가 아니라 상한 없음이다.
		assertThat(violationsOf(read("{\"monthlyTokenLimit\": null}"))).isEmpty();
	}

	@Test
	void 상한을_보낼_때만_범위를_검사한다() {
		assertThat(violationsOf(read("{\"monthlyTokenLimit\": 0}")))
				.anyMatch(message -> message.contains("0보다 커야"));
		assertThat(violationsOf(read("{\"storageLimitBytes\": -1}")))
				.anyMatch(message -> message.contains("0 이상"));
	}

	@Test
	void 보존기간은_보낼_때만_검사한다() {
		assertThat(violationsOf(read("{\"dataRetentionDays\": 100}"))).isNotEmpty();
		assertThat(violationsOf(read("{\"dataRetentionDays\": 180}"))).isEmpty();
	}

	private static UpdateOperationSettingRequest read(String json) {
		return MAPPER.readValue(json, UpdateOperationSettingRequest.class);
	}

	private static java.util.List<String> violationsOf(UpdateOperationSettingRequest request) {
		try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
			Validator validator = factory.getValidator();
			return validator.validate(request).stream()
					.map(jakarta.validation.ConstraintViolation::getMessage)
					.toList();
		}
	}
}
