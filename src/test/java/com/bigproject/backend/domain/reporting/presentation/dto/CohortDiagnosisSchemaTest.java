package com.bigproject.backend.domain.reporting.presentation.dto;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OP-05 응답의 <b>함정 세 개가 생성 타입에서 보이는지</b> 확인한다.
 *
 * <p>셋 다 값 자체는 정상인데 <b>읽는 쪽이 오해하기 쉬운</b> 것들이라, 설명이 스펙에 실려야
 * 프론트가 생성 타입(`schema.d.ts`)에서 바로 본다. 이 저장소에는 javadoc→OpenAPI 플러그인이
 * 없어 <b>javadoc은 스펙에 실리지 않는다</b> — {@code @Schema(description)}이라야 나간다.
 * 설명을 javadoc으로 되돌리면 이 테스트가 깨진다.
 */
class CohortDiagnosisSchemaTest {

	/** 도달 단계는 4단이 아니라 5단이다. 값이 0이라 증상이 늦게 드러나는 만큼 설명이 필요하다. */
	@Test
	void warnsThatTheReachScaleHasFiveStepsNotFour() {
		Map<String, Schema> properties = propertiesOf("ReachDistribution");

		assertThat(properties).containsKeys("level0", "level1", "level2", "level3", "level4", "unasked");
		assertThat(properties.get("level0").getDescription())
				.contains("5단")
				.contains("DistributionBar");
		// "못한 것"과 "안 물어본 것"을 합치지 말라는 경고가 unasked 쪽에도 있어야 한다.
		assertThat(properties.get("unasked").getDescription()).contains("level0");
	}

	/** 회차 단위는 gradedCount(사람×개념)와 totalCount(사람)의 단위가 다르다. */
	@Test
	void saysThatRoundCountsUseTwoDifferentUnits() {
		Map<String, Schema> properties = propertiesOf("RoundDiagnosis");

		assertThat(properties.get("gradedCount").getDescription()).contains("단위");
		assertThat(properties.get("totalCount").getDescription())
				.contains("단위가 다르다")
				.contains("300%");
	}

	/** 값이 비어 있는 게 아니라 축이 없는 것이다 — CSV가 빈 열을 내보내고 있다. */
	@Test
	void marksTheGroupShortfallRoundAsPermanentlyEmpty() {
		assertThat(propertiesOf("GroupShortfall").get("round").getDescription())
				.contains("항상 빈 문자열")
				.contains("CSV");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Schema> propertiesOf(String schemaName) {
		return ModelConverters.getInstance()
				.readAll(new AnnotatedType(CohortDiagnosisResponse.class))
				.get(schemaName)
				.getProperties();
	}
}
