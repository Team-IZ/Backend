package com.bigproject.backend.domain.reporting.presentation.dto;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TR-04 응답이 <b>화면이 필요로 하는 식별자·상태를 실제로 내보내는지</b> 못 박는다.
 *
 * <p>셋 다 조회 SQL은 이미 값을 읽고 있었는데 응답 DTO에 필드가 없어 버려지던 것들이다.
 * 값이 있는데 안 싣는 실수는 런타임에 드러나지 않으므로(응답이 200이라 화면만 조용히 못 그린다)
 * 스키마로 고정한다.
 */
class TraineeReportSchemaTest {

	/**
	 * 목록의 {@code id}는 <b>회차</b> ID다(미응시 회차도 키가 있어야 해서 그렇게 뒀다).
	 * 그래서 단건 조회 {@code GET /reports/{reportId}}를 부르려면 리포트 ID가 따로 필요하다 —
	 * 이 필드가 없으면 목록에서 상세로 넘어갈 수 없다.
	 */
	@Test
	void carriesTheReportIdSoTheListCanOpenASingleReport() {
		Map<String, Schema> properties = roundReportProperties();

		assertThat(properties).containsKey("reportId");
		assertThat(properties).containsKey("id");
	}

	/**
	 * 🔴 공개 범위({@code disclosureScope})는 응답에서 <b>없어졌다</b>(2026-08-19).
	 *
	 * <p>종전에는 화면이 {@code qa} 유무로 범위를 되짚지 않게 값으로 내려줬는데, 공개/비공개가
	 * 폐지되면서 되짚을 범위 자체가 사라졌다. 되살리면 화면이 죽은 축으로 다시 분기하게 된다.
	 */
	@Test
	void noLongerCarriesADisclosureScope() {
		assertThat(roundReportProperties()).doesNotContainKey("disclosureScope");
	}

	/**
	 * PARTIAL 리포트를 화면이 완전한 리포트와 구분할 수 있어야 한다.
	 *
	 * <p>문제 3개 중 1개가 실패해도 발행은 된다(결정 11). 이 값이 없으면 화면에 개념 카드가
	 * 2장만 뜨는데, 학생은 "원래 개념이 2개였나 보다" 하고 넘어간다 — 응답이 200이라
	 * 아무 데서도 드러나지 않는다.
	 *
	 * <p>설명까지 못 박는 이유는 <b>같은 이름이 OP-05에서 다른 뜻</b>이기 때문이다.
	 * 여기 PARTIAL은 AI 생성 실패(장애)이고 OP-05는 미응시·무효로 모수가 준 것(정상)이다.
	 * 문구를 재사용하면 한쪽이 정상 동작을 장애로 표시하게 된다. javadoc은 스펙에 닿지 않아
	 * ({@code therapi} 플러그인 없음) 이 구분이 프론트에 전달되는 경로는 {@code @Schema} 하나뿐이다.
	 */
	@Test
	void distinguishesPartialReportsAndWarnsThatOp05MeansSomethingElse() {
		Map<String, Schema> schemas = ModelConverters.getInstance()
				.readAll(new AnnotatedType(TraineeReportsResponse.class));

		assertThat(((Schema<?>) roundReportProperties().get("completionStatus")).get$ref())
				.isEqualTo("#/components/schemas/ReportCompletionStatus");
		assertThat(schemas.get("ReportCompletionStatus").getEnum())
				.containsExactlyInAnyOrder("FULL", "PARTIAL");

		// 설명이 타입에 있는 이유는 ReportCompletionStatus javadoc 참고 — $ref 옆 형제 필드는
		// 무시되므로 필드에 달면 값 목록과 설명 중 하나를 잃는다.
		assertThat(schemas.get("ReportCompletionStatus").getDescription())
				.as("javadoc은 스펙에 닿지 않는다 — 이 설명이 비면 프론트는 두 화면의 PARTIAL을 같은 문구로 쓴다")
				.contains("OP-05");
	}

	/** 개념 이름은 회차마다 반복되므로 이름으로 지목하면 엉뚱한 회차의 개념을 짚을 수 있다. */
	@Test
	void identifiesEachConceptByProblemIdNotJustItsName() {
		Map<String, Schema> schemas = ModelConverters.getInstance()
				.readAll(new AnnotatedType(TraineeReportsResponse.class));

		assertThat(schemas.get("ConceptReportResponse").getProperties())
				.containsKeys("problemId", "name");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Schema> roundReportProperties() {
		return ModelConverters.getInstance()
				.readAll(new AnnotatedType(TraineeReportsResponse.class))
				.get("RoundReportResponse")
				.getProperties();
	}
}
