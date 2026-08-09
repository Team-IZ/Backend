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
		CohortStatus status,

		@Schema(description = "기수 시작일", nullable = true) LocalDate startDate,
		@Schema(description = "기수 종료일", nullable = true) LocalDate endDate,

		@Schema(description = "⚠ 아직 채워지지 않는 값 — 항상 0이다. member 도메인 조인이 필요해 아직 연결되지 않았다.", example = "0")
		int traineeCount,

		@Schema(description = "⚠ 아직 채워지지 않는 값 — 항상 빈 배열이다. classroom 도메인 조인이 필요해 아직 연결되지 않았다. " +
				"담당 매니저가 필요하면 GET /cohorts/{cohortId}/classrooms를 함께 호출한다.")
		List<Manager> managers
) {
	// managers[]의 항목 타입은 공용 Manager 스키마다(같은 패키지). 예전에는 여기에 memberId가 Long인
	// 중첩 record가 따로 있었는데, springdoc이 단순 클래스 이름으로 키잉해 반 응답의 Manager(UUID)와
	// `Manager` 키 하나를 놓고 충돌했고 먼저 등록된 이쪽이 이겨서 스펙이 integer라고 거짓말했다(9차 R5).
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
}