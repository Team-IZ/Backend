package com.bigproject.backend.domain.organization.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 기관 상세 개요 하단의 <b>읽기전용</b> 기수 목록. 목업 SA-02 ① 개요의 `기수 · 읽기전용` 표에 대응한다.
 *
 * <p>목업 주석: "기수를 여기서 열지 않는다. 지원·과금 맥락의 읽기전용 가시성만 두고,
 * 학생 데이터는 테넌트 경계 밖이라 들어가지 않는다." — 그래서 조회 전용이며 개설·편성 API는 제공하지 않는다
 * (그쪽은 오퍼레이터의 OP-06 담당).
 */
@Schema(description = "기관 기수 목록 (슈퍼어드민 읽기전용)")
public record OrganizationCohortListResponse(
		UUID organizationId,
		List<Cohort> content
) {

	@Schema(description = "기수 요약")
	public record Cohort(
			UUID cohortId,
			String name,

			@Schema(description = "기수 상태. PLANNED(예정) / RUNNING(진행 중) / CLOSED(종료)", example = "RUNNING")
			String status,

			@Schema(description = "기수에 속한 반 수")
			int classCount,

			@Schema(description = "현재 소속 교육생 수(기수를 나간 인원 제외)")
			int traineeCount,

			@Schema(description = "기수 시작일. 미정이면 null", nullable = true)
			LocalDate startDate,

			@Schema(description = "기수 종료일. 미정이면 null", nullable = true)
			LocalDate endDate
	) {
	}
}
