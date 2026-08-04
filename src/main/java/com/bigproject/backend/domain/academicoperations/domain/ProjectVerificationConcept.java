package com.bigproject.backend.domain.academicoperations.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

// 활성 개념 세트에 포함된 검증 개념(테이블정의서 03_ORG project_verification_concept)
// 세트마다 정확히 3건이고 sequence_no는 1·2·3만 허용하며, 이 순서가 화면 "개념 3건 도달" 셀 순서가 된다.

@Entity
@Table(name = "project_verification_concept")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectVerificationConcept {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "project_concept_id", nullable = false, updatable = false)
	private UUID projectConceptId;

	@Column(name = "concept_set_id", nullable = false, updatable = false)
	private UUID conceptSetId;

	// 어느 기관 소속인지. 다른 기관 데이터가 보이면 안 되니까 조회할 때 항상 이 값도 같이 확인합니다.
	@Column(name = "org_id", nullable = false, updatable = false)
	private UUID orgId;

	@Column(name = "teaches_id", nullable = false, updatable = false)
	private UUID teachesId;

	@Column(name = "sequence_no", nullable = false)
	private Integer sequenceNo;
}
