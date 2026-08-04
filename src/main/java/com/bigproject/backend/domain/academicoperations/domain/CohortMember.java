package com.bigproject.backend.domain.academicoperations.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

// 기수에 소속된 교육생/구성원 한 명을 나타내는 엔티티
// status는 체크 제약이 없는 카탈로그형 코드(INVITED, ACTIVE 등)라 enum 대신 String으로 둠

@Entity
@Table(name = "cohort_member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CohortMember {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "cohort_member_id", nullable = false, updatable = false)
	private UUID cohortMemberId;

	@Column(name = "cohort_id", nullable = false, updatable = false)
	private UUID cohortId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	// 어느 기관 소속인지. 다른 기관 데이터가 보이면 안 되니까 조회할 때 항상 이 값도 같이 확인합니다.
	@Column(name = "org_id", nullable = false, updatable = false)
	private UUID orgId;

	@Column(name = "status", nullable = false, length = 100)
	private String status;

	@Column(name = "joined_at", nullable = false)
	private OffsetDateTime joinedAt;

	@Column(name = "left_at")
	private OffsetDateTime leftAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;
}
