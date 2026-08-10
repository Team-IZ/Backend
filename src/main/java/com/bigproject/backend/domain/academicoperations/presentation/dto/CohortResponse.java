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

		@Schema(description = "재적 교육생 수. 이 기수에 등록돼 있고 아직 나가지 않은 인원이다(10차 R3). " +
				"중도 이탈자는 빠지므로 교육생 명단 조회의 전체 건수보다 작을 수 있다.", example = "208")
		int traineeCount,

		@Schema(description = "⚠ 아직 채워지지 않는 값 — 항상 빈 배열이다. classroom 도메인 조인이 필요해 아직 연결되지 않았다. " +
				"담당 매니저가 필요하면 GET /cohorts/{cohortId}/classrooms를 함께 호출한다.")
		List<Manager> managers
) {
	// managers[]의 항목 타입은 공용 Manager 스키마다(같은 패키지). 예전에는 여기에 memberId가 Long인
	// 중첩 record가 따로 있었는데, springdoc이 단순 클래스 이름으로 키잉해 반 응답의 Manager(UUID)와
	// `Manager` 키 하나를 놓고 충돌했고 먼저 등록된 이쪽이 이겨서 스펙이 integer라고 거짓말했다(9차 R5).
	// managers는 classroom 도메인이 준비되기 전까지 빈 값으로 채운다.

	/**
	 * 재적 인원을 아직 세지 않은 자리에서 쓴다 — 기수를 <b>방금 만들었거나 종료한</b> 응답이며,
	 * 그 순간의 인원은 각각 0명·직전과 동일이라 별도 집계가 의미 없다.
	 * 목록·단건 조회처럼 실제 인원을 보여줘야 하는 곳은 {@link #from(Cohort, int)}를 쓴다.
	 */
	public static CohortResponse from(Cohort cohort) {
		return from(cohort, 0);
	}

	public static CohortResponse from(Cohort cohort, int traineeCount) {
		return new CohortResponse(
				cohort.getCohortId(),
				cohort.getOrgId(),
				cohort.getName(),
				cohort.getStatus(),
				cohort.getStartDate(),
				cohort.getEndDate(),
				traineeCount,
				List.of()
		);
	}
}