package com.bigproject.backend.global.config;

import com.bigproject.backend.domain.organization.presentation.dto.CreateOrganizationRequest;
import com.bigproject.backend.domain.organization.presentation.dto.OrganizationResponse;
import com.bigproject.backend.domain.reporting.presentation.dto.TraineeReportsResponse;
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
	 * 잃었다 — 상태에 따라 키가 빠지는 몇 개 때문에 <b>항상 오는 필드까지</b> optional로 나갔다.
	 * 어노테이션을 필드 단위로 내려 항상 오는 값과 조건부인 값을 갈랐다.
	 *
	 * <p>레코드 전체가 아니라 <b>필드 하나</b>에 걸린 {@code @JsonInclude}도 키를 뺀다. 그 표시는
	 * record 컴포넌트에 남지 않고 접근자로 전파되므로, 컴포넌트만 보면 빠지는 키가 required로
	 * 나간다 — 스펙이 거짓말을 하는 쪽이라 더 나쁘다.
	 *
	 * <p>종전에는 {@code ReportDisclosureResponse}·{@code ManagedReportListResponse}로 확인했는데,
	 * 공개/비공개 폐지(2026-08-19)로 둘 다 없어져 같은 성질을 가진
	 * {@code RoundReportResponse}로 옮겼다 — 회차 상태에 따라 본문 필드가 통째로 빠지는 레코드다.
	 */
	@Test
	void marksOnlyTheFieldsThatCanDisappearAsOptional() {
		Schema<?> schema = resolve(TraineeReportsResponse.RoundReportResponse.class)
				.get("RoundReportResponse");

		assertThat(schema.getRequired())
				// 회차 카드는 상태와 무관하게 이 셋을 항상 준다.
				.contains("id", "label", "status")
				// 나머지는 PUBLISHED 등 특정 상태에서만 실린다.
				.doesNotContain("reportId", "publishAfter", "publishedAt", "curriculum",
						"completionStatus", "concepts", "retryState");
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
