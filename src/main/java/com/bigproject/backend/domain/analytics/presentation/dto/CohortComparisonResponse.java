package com.bigproject.backend.domain.analytics.presentation.dto;

import com.bigproject.backend.domain.analytics.domain.ChangeDirection;
import com.bigproject.backend.domain.analytics.domain.ComparisonEmptyState;
import com.bigproject.backend.domain.analytics.domain.ComparisonSort;
import com.bigproject.backend.domain.analytics.domain.ConceptPresence;
import com.bigproject.backend.domain.analytics.domain.NotComparableReason;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Schema(description = "두 기수의 검증 개념별 평균 도달 단계 비교 격자")
public record CohortComparisonResponse(
		@Schema(description = "이번 기수")
		CohortRef targetCohort,
		@Schema(description = "지난 기수이며 비교 대상을 고르지 않았으면 null입니다.")
		CohortRef baselineCohort,
		@Schema(description = "비교 드롭다운 후보이며 같은 기관의 다른 기수를 최근 시작 순으로 내려줍니다.")
		List<BaselineOption> availableBaselineCohorts,
		@Schema(description = """
				격자를 그릴 수 없는 원인이며 비교 가능하면 null입니다.
				값이 있으면 concepts는 항상 빈 배열입니다.
				""")
		ComparisonEmptyState emptyStateCode,
		@Schema(description = "색 눈금 정의")
		LevelScale levelScale,
		@Schema(description = "변화 방향 경계값")
		ChangeThreshold changeThreshold,
		@Schema(description = "실제로 적용된 정렬 기준")
		ComparisonSort appliedSort,
		@Schema(description = """
				같은 교안 버전을 쓴 개념만 남겼는지 여부입니다.
				교안이 바뀌면 평균 차이가 교육생 변화인지 교안 변화인지 갈라 볼 수 없어 걸러냅니다.
				""")
		boolean sameCurriculumOnly,
		@Schema(description = "검증 개념 행 목록")
		List<ConceptComparison> concepts
) {

	@Schema(description = "기수 표시 정보")
	public record CohortRef(
			UUID cohortId,
			@Schema(description = "기수 표시명이며 cohort에는 기수 번호 컬럼이 없어 이름을 그대로 씁니다.", example = "7기")
			String cohortName
	) {
	}

	@Schema(description = "비교 후보 기수")
	public record BaselineOption(
			UUID cohortId,
			String cohortName,
			@Schema(description = "발행된 수업 진단 리포트가 있어 실제로 비교할 수 있는지 여부입니다.")
			boolean comparable
	) {
	}

	@Schema(description = """
			절대 색 눈금이며 MG-02 히트맵과 같은 값입니다.
			0~4 정수 다섯 단계에 다섯 색을 대응시키되 평균은 연속값이므로
			각 정수를 중심으로 폭 1(양 끝은 0.5)의 구간을 두고 반올림으로 배정합니다.
			levelBand는 서버가 미리 계산해 내려주므로 클라이언트가 다시 계산할 필요는 없습니다.
			""")
	public record LevelScale(
			@Schema(description = "최소 도달 단계이며 1단도 통과하지 못한 응시가 있어 0을 포함합니다.", example = "0")
			int min,
			@Schema(description = "최대 도달 단계", example = "4")
			int max,
			@Schema(description = "밴드 경계값이며 경계에 걸친 값은 위쪽 밴드로 올립니다.", example = "[0.5, 1.5, 2.5, 3.5]")
			List<BigDecimal> bandThresholds
	) {
	}

	@Schema(description = "나빠짐·좋아짐 경계값이며 대칭입니다.")
	public record ChangeThreshold(
			@Schema(description = "이 값 이하면 나빠짐입니다.", example = "-0.3")
			BigDecimal worsened,
			@Schema(description = "이 값 이상이면 좋아짐입니다.", example = "0.3")
			BigDecimal improved
	) {
	}

	@Schema(description = "검증 개념 한 행")
	public record ConceptComparison(
			@Schema(description = "기수를 넘어 안정적인 검증 개념 ID이며 두 기수를 잇는 매칭 키입니다.")
			UUID teachesId,
			@Schema(description = "검증 개념 이름", example = "Graph 구성")
			String conceptName,
			@Schema(description = "교안 출처 표기")
			ConceptSource source,
			@Schema(description = "이번 기수 값")
			CohortConceptValue target,
			@Schema(description = "지난 기수 값")
			CohortConceptValue baseline,
			@Schema(description = "변화 요약")
			ConceptChange change,
			@Schema(description = "교안 버전 변화")
			CurriculumVersionChange curriculumVersion
	) {
	}

	@Schema(description = "개념이 나온 교안 위치이며 이번 기수 기준이고 없으면 지난 기수 기준입니다.")
	public record ConceptSource(
			@Schema(description = "교안 이름", example = "AI_LLMOps")
			String curriculumTitle,
			@Schema(description = "교안 장 순번이며 화면의 '4장'입니다.", example = "4")
			Integer sectionSequenceNo,
			@Schema(description = "교안 장 제목")
			String sectionTitle,
			@Schema(description = "개념이 시작되는 쪽수", example = "62")
			Integer pageStart,
			@Schema(description = "개념이 끝나는 쪽수", example = "65")
			Integer pageEnd,
			@Schema(description = "개념을 검증한 회차 번호이며 여러 회차면 가장 최근 회차입니다.", example = "3")
			Integer roundNo,
			@Schema(description = "회차 이름이며 화면의 '미프 3차'입니다.")
			String roundLabel
	) {
	}

	@Schema(description = "한 기수의 개념 값")
	public record CohortConceptValue(
			@Schema(description = """
					평균 도달 단계이며 Σ(도달 단계 × 인원) / Σ인원입니다.
					개념이 없거나 분모가 0이면 0단이 아니라 null입니다.
					""", example = "2.50")
			BigDecimal averageReachedLevel,
			@Schema(description = "평균이 속한 색 밴드(0~4)이며 값이 없으면 null입니다.", example = "3")
			Integer levelBand,
			@Schema(description = "평균의 분모가 된 인원", example = "24")
			long participantCount,
			@Schema(description = "집계에서 빠진 인원", example = "1")
			long missingCount,
			@Schema(description = "PRESENT / ABSENT_IN_COHORT(그 기수에 없던 개념) / MERGED(다른 개념으로 병합)")
			ConceptPresence presence,
			@Schema(description = "발행 스냅샷의 집계 상태이며 개념이 없으면 null입니다.", example = "SINGLE_SOURCE")
			String aggregationStatus
	) {
	}

	@Schema(description = "지난 기수 대비 변화")
	public record ConceptChange(
			@Schema(description = "WORSE / SIMILAR / BETTER / NOT_COMPARABLE")
			ChangeDirection direction,
			@Schema(description = """
					이번 기수 평균 - 지난 기수 평균이며 비교할 수 없으면 null입니다.
					화면에 보이는 두 평균을 그대로 뺀 값이라 표시값과 항상 일치합니다.
					""", example = "-0.40")
			BigDecimal delta,
			@Schema(description = "비교할 수 없는 이유이며 비교 가능하면 null입니다.")
			NotComparableReason notComparableReasonCode
	) {
	}

	@Schema(description = "교안 버전 변화이며 화면의 'v1 → v2' 또는 'v3 · 그대로'입니다.")
	public record CurriculumVersionChange(
			@Schema(description = "지난 기수에서 쓴 교안 버전", example = "1")
			Integer baselineVersionNo,
			@Schema(description = "이번 기수에서 쓴 교안 버전", example = "2")
			Integer targetVersionNo,
			@Schema(description = "두 버전이 모두 있고 서로 다르면 true입니다.")
			boolean versionChanged
	) {
	}
}
