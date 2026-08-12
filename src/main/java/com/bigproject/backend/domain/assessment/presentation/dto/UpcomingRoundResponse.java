package com.bigproject.backend.domain.assessment.presentation.dto;

import com.bigproject.backend.domain.assessment.domain.TraineeHomeRound;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * 예정 구획 카드. 회차명·제출일자·이해도 확인 시작·종료일자만 보여주므로
 * current(35필드)와 필드 집합을 공유하지 않는다.
 */
@Schema(description = "예정 회차. 7필드로 고정한다.")
public record UpcomingRoundResponse(
		UUID assessmentRoundId,
		Integer roundNo,
		@Schema(example = "미프 4차") String roundName,
		@Schema(description = "항상 PLANNED", example = "PLANNED",
				allowableValues = {"PLANNED", "OPEN", "CLOSED", "COMPLETED"}) String roundStatus,
		Instant submissionDueAt,
		@Schema(description = """
				이해도 확인 시작일자. **PLANNED 회차에서는 null일 수 있다** — \
				ck_project_assessment_round_assessment_window_required가 PLANNED만 면제하기 때문이다.""",
				nullable = true)
		Instant roundAssessmentOpenAt,
		@Schema(description = "이해도 확인 종료일자. 위와 같은 이유로 null 가능", nullable = true)
		Instant roundAssessmentDueAt
) {
	public static UpcomingRoundResponse from(TraineeHomeRound round) {
		return new UpcomingRoundResponse(
				round.assessmentRoundId(),
				round.roundNo(),
				round.roundName(),
				round.roundStatus(),
				round.submissionDueAt(),
				round.roundAssessmentOpenAt(),
				round.roundAssessmentDueAt()
		);
	}
}
