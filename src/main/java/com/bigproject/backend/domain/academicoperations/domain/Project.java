package com.bigproject.backend.domain.academicoperations.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

// 기수 전체가 공통으로 수행하는 프로젝트 원장(테이블정의서 03_ORG project)
// 화면의 "미프 N차"는 project_assessment_round.round_no가 아니라 삭제되지 않은 MINI_PROJECT를
// sequence_no 순으로 재번호화한 조회값이므로, 순서 계산은 ProjectRepository에서 수행한다.

@Entity
@Table(name = "project")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	// 어느 기관 소속인지. 다른 기관 데이터가 보이면 안 되니까 조회할 때 항상 이 값도 같이 확인합니다.
	@Column(name = "org_id", nullable = false, updatable = false)
	private UUID orgId;

	@Column(name = "cohort_id", nullable = false, updatable = false)
	private UUID cohortId;

	@Column(name = "name", nullable = false, length = 200)
	private String name;

	// 기수 내 전체 프로젝트 운영 순서. MINI_PROJECT만 추린 표시 순서(analysis_sequence_no)와는 다른 값이다.
	@Column(name = "sequence_no", nullable = false)
	private Integer sequenceNo;

	// CHECK 제약이 있는 코드지만 이 도메인에서는 조회 분기에만 쓰므로 String으로 둔다: MINI_PROJECT / BIG_PROJECT
	@Column(name = "project_category", nullable = false, length = 30)
	private String projectCategory;

	@Column(name = "start_date", nullable = false)
	private LocalDate startDate;

	@Column(name = "end_date", nullable = false)
	private LocalDate endDate;

	@Column(name = "lifecycle_status", nullable = false, length = 30)
	private String lifecycleStatus;

	@Column(name = "deleted_at")
	private OffsetDateTime deletedAt;
}
