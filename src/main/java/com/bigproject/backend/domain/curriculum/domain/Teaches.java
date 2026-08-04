package com.bigproject.backend.domain.curriculum.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

// 여러 교안 버전에서 재사용하는 기관 단위 표준 학습 개념 원장(테이블정의서 05_CUR teaches)
// 교안별 표현·페이지는 CurriculumTeachesMapping이 소유하고 여기서는 표준명만 화면 표시에 사용한다.

@Entity
@Table(name = "teaches")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Teaches {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "teaches_id", nullable = false, updatable = false)
	private UUID teachesId;

	// 어느 기관 소속인지. 다른 기관 데이터가 보이면 안 되니까 조회할 때 항상 이 값도 같이 확인합니다.
	@Column(name = "org_id", nullable = false, updatable = false)
	private UUID orgId;

	@Column(name = "canonical_name", nullable = false, length = 200)
	private String canonicalName;

	// ACTIVE / INACTIVE / MERGED
	@Column(name = "status", nullable = false, length = 30)
	private String status;
}
