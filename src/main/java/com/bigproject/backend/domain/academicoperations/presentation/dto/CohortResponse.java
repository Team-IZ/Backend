package com.bigproject.backend.domain.academicoperations.presentation.dto;

import com.bigproject.backend.domain.academicoperations.domain.Cohort;
import com.bigproject.backend.domain.academicoperations.domain.CohortStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "기수 정보")
public record CohortResponse(
		@Schema(description = "기수 ID") UUID cohortId,
		@Schema(description = "소속 기관 ID") UUID organizationId,
		@Schema(description = "기수명", example = "7기") String name,

		@Schema(description = "PLANNED(개설 예정) / RUNNING(진행 중) / CLOSED(종료)")
		CohortStatus status,

		@Schema(description = "기수 시작일") LocalDate startDate,
		@Schema(description = "기수 종료일") LocalDate endDate,

		@Schema(description = "⚠ 아직 채워지지 않는 값 — 항상 0이다. member 도메인 조인이 필요해 아직 연결되지 않았다.", example = "0")
		int traineeCount,

		@Schema(description = "⚠ 아직 채워지지 않는 값 — 항상 빈 배열이다. classroom 도메인 조인이 필요해 아직 연결되지 않았다. " +
				"담당 매니저가 필요하면 GET /cohorts/{cohortId}/classrooms를 함께 호출한다.")
		List<Manager> managers
) {
	// traineeCount/managers는 member·classroom 도메인이 준비되기 전까지 빈 값으로 채운다.
	public static CohortResponse from(Cohort cohort) {
		return new CohortResponse(
				cohort.getCohortId(),
				cohort.getOrgId(),
				cohort.getName(),
				cohort.getStatus(),
				cohort.getStartDate(),
				cohort.getEndDate(),
				0,
				List.of()
		);
	}

	@Schema(description = "담당 매니저 한 명(현재 미사용 — 항상 빈 배열로 내려감)")
	public record Manager(
			@Schema(description = "매니저의 회원 ID") Long memberId,
			@Schema(description = "매니저 이름") String name
	) {
	}
}