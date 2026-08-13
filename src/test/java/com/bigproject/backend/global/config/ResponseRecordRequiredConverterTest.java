package com.bigproject.backend.global.config;

import com.bigproject.backend.domain.disclosure.presentation.dto.ReportDisclosureResponse;
import com.bigproject.backend.domain.organization.presentation.dto.CreateOrganizationRequest;
import com.bigproject.backend.domain.organization.presentation.dto.OrganizationResponse;
import com.bigproject.backend.domain.reporting.presentation.dto.ManagedReportListResponse;
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

	/**
	 * 17차 R2. 예전에는 클래스 레벨 {@code @JsonInclude(NON_NULL)} 하나로 전 필드가 required를
	 * 잃었다 — {@code scope}·{@code publishedAt}·{@code releasedAt} 셋만 상태에 따라 키가 빠지는데도
	 * {@code reportId}처럼 항상 오는 필드까지 optional로 나갔다. 어노테이션을 필드 단위로 내려
	 * 항상 오는 값과 조건부인 값을 갈랐다.
	 */
	@Test
	void marksOnlyTheFieldsThatCanDisappearAsOptional() {
		Schema<?> schema = resolve(ReportDisclosureResponse.class).get("ReportDisclosureResponse");

		assertThat(schema.getRequired())
				.contains("reportId", "assessmentRoundId", "releaseStatus", "bodyVisible", "visibleFields")
				.doesNotContain("scope", "publishedAt", "releasedAt");
	}

	/**
	 * 레코드 전체가 아니라 <b>필드 하나</b>에 걸린 {@code @JsonInclude}도 키를 뺀다.
	 * 그 표시는 record 컴포넌트에 남지 않고 접근자로 전파되므로, 컴포넌트만 보면
	 * 빠지는 키가 required로 나간다 — 스펙이 거짓말을 하는 쪽이라 더 나쁘다.
	 */
	@Test
	void skipsOnlyTheFieldsThatCanDisappear() {
		Schema<?> schema = resolve(ManagedReportListResponse.class).get("ManagedReportItem");

		assertThat(schema.getRequired())
				.contains("reportId", "roundNo", "releaseStatus", "bodyVisible")
				.doesNotContain("roundName", "publishedAt", "scope", "releasedAt");
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
