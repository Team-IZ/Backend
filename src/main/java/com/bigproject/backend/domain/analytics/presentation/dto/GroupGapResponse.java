package com.bigproject.backend.domain.analytics.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Schema(description = """
		기수 전체의 집단 미달 목록.

		한 검증 개념에서 반 인원의 절반을 넘는 인원이 2단 이하면 개인 문제가 아니라 반 문제로 봅니다.
		목록이 비어 있을 때 emptyReasonCode로 '평가 자체가 없음'과 '미달이 실제로 0건'을 구분합니다.
		""")
public record GroupGapResponse(
		UUID cohortId,
		@Schema(description = "미달 판정 경계이며 이 비율을 초과해야 미달입니다.", example = "0.5")
		BigDecimal underperformanceThresholdRatio,
		@Schema(description = "2단 이하로 세는 최대 도달 단계", example = "2")
		int lowLevelMaxStage,
		@Schema(description = "미달 여부와 무관하게 평가가 이뤄진 회차 × 반 × 개념 조합 수", example = "90")
		int evaluatedClassConceptCount,
		@Schema(description = """
				목록이 비어 있는 이유이며 미달 건이 있으면 null입니다.
				NO_ELIGIBLE_PARTICIPANT(평가된 조합이 없음) / NO_GROUP_UNDERPERFORMANCE(평가했으나 미달 0건)
				""", nullable = true)
		GroupGapEmptyReason emptyReasonCode,
		@Schema(description = "미달 비율 내림차순 목록")
		List<GroupGapRow> gaps
) {

	@Schema(description = "빈 목록의 원인")
	public enum GroupGapEmptyReason {
		// 아직 아무 회차·반·개념도 평가되지 않았다.
		NO_ELIGIBLE_PARTICIPANT,
		// 평가는 이뤄졌고 미달 조합이 실제로 0건이다.
		NO_GROUP_UNDERPERFORMANCE
	}

	@Schema(description = "집단 미달 한 건")
	public record GroupGapRow(
			UUID assessmentRoundId,
			@Schema(description = "회차 번호이며 프로젝트 안에서만 유일합니다.", example = "2")
			int roundNo,
			String roundName,
			UUID projectId,
			@Schema(description = "화면의 '미프 2차'에 해당합니다.", example = "미프 2차")
			String projectName,
			UUID classId,
			String className,
			UUID teachesId,
			@Schema(description = "검증 개념 이름", example = "Graph 구성")
			String conceptName,
			@Schema(description = "2단 이하 인원이며 미응시·무효 확정은 세지 않습니다.", example = "14")
			long lowLevelCount,
			@Schema(description = "반 인원 전체", example = "25")
			long classMemberCount,
			@Schema(description = "lowLevelCount / classMemberCount", example = "0.5600")
			BigDecimal lowLevelRate
	) {
	}
}
