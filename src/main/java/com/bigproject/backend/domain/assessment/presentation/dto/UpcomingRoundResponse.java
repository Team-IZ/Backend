package com.bigproject.backend.domain.assessment.presentation.dto;

import com.bigproject.backend.domain.assessment.domain.AssessmentRoundStatus;
import com.bigproject.backend.domain.assessment.domain.TraineeHomeRound;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * 예정 구획 카드. 회차명·제출일자·이해도 확인 시작·종료일자만 보여주므로
 * current(35필드)와 필드 집합을 공유하지 않는다.
 *
 * <p>예정 구획에는 {@code PLANNED} 회차와, <b>current로 뽑히지 않았고 제출 마감이 아직 남은
 * {@code OPEN} 회차</b>가 함께 담긴다. 자세한 이유는
 * {@code AssessmentRoundQueryService} 클래스 주석을 볼 것.
 */
@Schema(description = "예정 회차. 7필드로 고정한다.")
public record UpcomingRoundResponse(
		UUID assessmentRoundId,
		Integer roundNo,
		@Schema(example = "미프 4차") String roundName,
		@Schema(description = """
				`PLANNED` 또는 `OPEN`. **OPEN이면 제출 마감이 아직 남은 회차다** — \
				같은 시점에 OPEN 회차가 둘 이상일 때 current가 아닌 쪽이 여기로 온다.""",
				example = "PLANNED",
				implementation = AssessmentRoundStatus.class) String roundStatus,
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
