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
	 * 공개 범위를 값으로 내려준다. 없으면 화면이 {@code qa} 유무로 되짚어야 하는데,
	 * {@code FULL}인데 문항이 아직 없어 {@code qa}가 비는 경우를 {@code SUMMARY}로 오인한다.
	 */
	@Test
	void tellsTheDisclosureScopeInsteadOfMakingTheScreenGuessFromQa() {
		Map<String, Schema> schemas = ModelConverters.getInstance()
				.readAll(new AnnotatedType(TraineeReportsResponse.class));

		assertThat(((Schema<?>) roundReportProperties().get("disclosureScope")).get$ref())
				.isEqualTo("#/components/schemas/DisclosureScope");
		assertThat(schemas.get("DisclosureScope").getEnum())
				.containsExactlyInAnyOrder("SUMMARY", "PRIVATE", "FULL");
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
