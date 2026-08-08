package com.bigproject.backend.global.config;

import com.bigproject.backend.domain.disclosure.presentation.dto.ReportDisclosureResponse;
import com.bigproject.backend.domain.organization.presentation.dto.CreateOrganizationRequest;
import com.bigproject.backend.domain.organization.presentation.dto.OrganizationResponse;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 응답 필드가 optional로 생성되면 화면 코드가 반드시 오는 값에도 {@code ?.}·{@code !}를 남발하게 된다.
 * record는 값이 null이어도 키가 나가므로 <b>전부 required</b>가 맞다.
 */
class ResponseRecordRequiredConverterTest {

	private final ModelConverters converters = withConverter();

	@Test
	void marksEveryComponentOfAResponseRecordRequired() {
		Schema<?> schema = resolve(OrganizationResponse.class).get("OrganizationResponse");

		assertThat(schema.getRequired())
				.containsAll(schema.getProperties().keySet())
				.contains("organizationId", "name", "slug", "deletedAt");
	}

	@Test
	void marksNestedResponseRecordsToo() {
		Map<String, Schema> schemas = resolve(OrganizationResponse.class);

		assertThat(schemas.get("Operator").getRequired()).containsExactlyInAnyOrder("memberId", "name", "email");
		assertThat(schemas.get("CohortCounts").getRequired()).containsExactlyInAnyOrder("total", "running", "closed");
	}

	@Test
	void leavesRequestDtosToBeanValidation() {
		Schema<?> schema = resolve(CreateOrganizationRequest.class).get("CreateOrganizationRequest");

		// @NotBlank·@NotNull이 붙은 것만 required다. 선택 입력까지 required가 되면 안 된다.
		assertThat(schema.getRequired()).containsExactlyInAnyOrder("name", "dataRetentionDays");
	}

	@Test
	void skipsRecordsWhoseKeysCanDisappear() {
		// @JsonInclude(NON_NULL)이라 상태에 따라 키 자체가 빠진다 — "항상 온다"고 말할 수 없다.
		Schema<?> schema = resolve(ReportDisclosureResponse.class).get("ReportDisclosureResponse");

		assertThat(schema.getRequired()).isNullOrEmpty();
	}

	private Map<String, Schema> resolve(Class<?> type) {
		return converters.readAll(new AnnotatedType(type));
	}

	private static ModelConverters withConverter() {
		ModelConverters instance = new ModelConverters();
		instance.addConverter(new ResponseRecordRequiredConverter());
		return instance;
	}
}
