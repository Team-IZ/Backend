package com.bigproject.backend.domain.assessment.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

// 프로젝트 내부의 코드 제출 마감·분석 실행 단위인 공통 회차 원장(테이블정의서 04_PLAN project_assessment_round)
// MINI_PROJECT는 활성 회차가 정확히 1건이며 그 회차가 고정 참조한 concept_set_id가 검증 개념 3건을 결정한다.

@Entity
@Table(name = "project_assessment_round")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectAssessmentRound {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "assessment_round_id", nullable = false, updatable = false)
	private UUID assessmentRoundId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	// 어느 기관 소속인지. 다른 기관 데이터가 보이면 안 되니까 조회할 때 항상 이 값도 같이 확인합니다.
	@Column(name = "org_id", nullable = false, updatable = false)
	private UUID orgId;

	@Column(name = "cohort_id", nullable = false, updatable = false)
	private UUID cohortId;

	// MINI_PROJECT는 OPEN 전이 전에 확정되고 BIG_PROJECT에서는 NULL이다.
	@Column(name = "concept_set_id")
	private UUID conceptSetId;

	@Column(name = "round_no", nullable = false)
	private Integer roundNo;

	@Column(name = "round_name", nullable = false, length = 200)
	private String roundName;

	// PLANNED / OPEN / CLOSED / COMPLETED
	@Column(name = "status", nullable = false, length = 100)
	private String status;

	@Column(name = "deleted_at")
	private OffsetDateTime deletedAt;
}
