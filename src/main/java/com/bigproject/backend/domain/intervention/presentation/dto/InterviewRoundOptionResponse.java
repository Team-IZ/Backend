package com.bigproject.backend.domain.intervention.presentation.dto;

import com.bigproject.backend.domain.intervention.application.InterviewService.RoundOptionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** 면담 목록 회차 드롭다운 항목. */
@Schema(description = "면담 회차 옵션")
public record InterviewRoundOptionResponse(

		UUID assessmentRoundId,

		@Schema(description = """
				드롭다운 문구. `round_no`가 프로젝트 안에서만 유일해 **프로젝트명을 함께 붙인다** —
				안 붙이면 서로 다른 프로젝트의 1차가 목록에 똑같이 두 번 보인다.
				""", example = "미니프로젝트 3차")
		String label,

		@Schema(description = "프로젝트 안에서의 회차 번호. 기수 전체에서 유일하지 않다", example = "3")
		int roundNo,

		@Schema(description = "회차 상태 `PLANNED` / `OPEN` / `CLOSED` / `COMPLETED`", example = "CLOSED")
		String status) {

	public static InterviewRoundOptionResponse from(RoundOptionView view) {
		return new InterviewRoundOptionResponse(
				view.assessmentRoundId(), view.label(), view.roundNo(), view.status());
	}
}
