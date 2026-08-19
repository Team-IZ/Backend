package com.bigproject.backend.domain.assessment.presentation.dto;

import com.bigproject.backend.domain.assessment.domain.MeasurementAttemptStatus;
import com.bigproject.backend.domain.assessment.domain.TraineeHomeRound;
import com.bigproject.backend.domain.assessment.domain.TraineeRepresentativeStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 지난 회차 카드. 회차명·이해도 검증 세션 완료 여부·다시 보기 상태만 보여준다.
 *
 * <p>"완료 여부"를 위한 별도 boolean은 만들지 않는다. {@code representativeStatus}가
 * 완료({@code ASSESSMENT_COMPLETED})와 미완료 사유({@code ASSESSMENT_WINDOW_CLOSED} ·
 * {@code SUBMISSION_MISSED} · {@code ANALYSIS_FAILED})를 이미 구분하므로,
 * 파생값을 더하면 계약이 둘로 갈린다.
 */
@Schema(description = "지난 회차. 8필드로 고정한다.")
public record PastRoundResponse(
		UUID assessmentRoundId,
		Integer roundNo,
		@Schema(example = "미프 2차") String roundName,
		@Schema(description = "세션 완료 여부 판정용. ASSESSMENT_COMPLETED이면 완료",
				example = "ASSESSMENT_COMPLETED",
				implementation = TraineeRepresentativeStatus.class)
		String representativeStatus,
		// 19차 R3 회신으로 값 집합을 확정했다. CurrentRoundResponse.reviewStatus와 같은 축이다.
		@Schema(description = "다시 보기 응시 상태. **배정이 없으면 null**", nullable = true,
				implementation = MeasurementAttemptStatus.class) String reviewStatus,
		@Schema(description = "완료한 다시 보기 건수", example = "1") int completedReviewCount,
		UUID reportId,
		@Schema(description = "reportPublishStatus = PUBLISHED일 때만 true") boolean canViewReport
) {
	public static PastRoundResponse from(TraineeHomeRound round) {
		return new PastRoundResponse(
				round.assessmentRoundId(),
				round.roundNo(),
				round.roundName(),
				round.representativeStatus(),
				round.reviewStatus(),
				round.completedReviewCount(),
				round.reportId(),
				round.canViewReport()
		);
	}
}
