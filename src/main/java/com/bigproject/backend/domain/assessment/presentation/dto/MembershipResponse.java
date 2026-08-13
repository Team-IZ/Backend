package com.bigproject.backend.domain.assessment.presentation.dto;

import com.bigproject.backend.domain.assessment.domain.TraineeHomeRound;
import com.bigproject.backend.domain.assessment.domain.TraineeMembership;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "기수 스코프 소속. 팀은 회차마다 바뀌므로 여기가 아니라 current에 있다.")
public record MembershipResponse(
		@Schema(description = "기수 ID. 기수 미소속이면 전 필드 null", nullable = true) UUID cohortId,
		@Schema(description = "기수 표시명", example = "7기", nullable = true) String cohortName,
		@Schema(description = "반 ID. 반 미배정이면 null", nullable = true) UUID classId,
		@Schema(description = "반 표시명", example = "A반", nullable = true) String className
) {
	public static MembershipResponse from(TraineeHomeRound round) {
		return new MembershipResponse(
				round.cohortId(),
				round.cohortName(),
				round.classId(),
				round.className()
		);
	}

	public static MembershipResponse from(TraineeMembership membership) {
		return new MembershipResponse(
				membership.cohortId(),
				membership.cohortName(),
				membership.classId(),
				membership.className()
		);
	}
}
