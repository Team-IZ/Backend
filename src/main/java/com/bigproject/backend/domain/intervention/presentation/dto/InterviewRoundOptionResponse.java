package com.bigproject.backend.domain.intervention.presentation.dto;

import com.bigproject.backend.domain.intervention.application.InterviewService.RoundOptionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 면담 목록 회차 드롭다운 항목.
 *
 * <p>32차 R10 — <b>히트맵(MG-02)도 이 조회를 쓴다.</b> 이름은 면담이지만 내용은 「담당 기수의
 * 회차 목록」이라 회차를 골라야 그릴 수 있는 화면이면 어디든 맞는다.
 */
@Schema(description = "면담 회차 옵션. 회차를 골라야 그릴 수 있는 화면(면담 목록·히트맵)이 공용으로 쓴다")
public record InterviewRoundOptionResponse(

		UUID assessmentRoundId,

		@Schema(description = """
				그 회차가 속한 프로젝트 ID(32차 R10).

				회차와 **짝으로** 필요한 화면이 있어 함께 싣는다 — 히트맵은 `projectId`와
				`assessmentRoundId`가 둘 다 필수인데, 종전에는 그 짝을 주는 조회가 교육생 명부뿐이라
				히트맵이 **격자와 무관한 명부를 먼저 받아야** 했다. 이 값이 붙으면서 그 왕복이 없어진다.
				""")
		UUID projectId,

		@Schema(description = """
				그 회차가 속한 기수 ID.

				한 매니저가 여러 기수에서 반을 맡을 수 있어(종료 기수를 되돌아보는 경우) 회차만으로는
				어느 기수의 것인지 가릴 수 없다. 화면이 보고 있는 기수와 대조할 수 있도록 함께 싣는다.
				""")
		UUID cohortId,

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
				view.assessmentRoundId(), view.projectId(), view.cohortId(), view.label(),
				view.roundNo(), view.status());
	}
}
