package com.bigproject.backend.domain.classroom.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

// 교육생(cohort_member)이 특정 반(class)에 배정된 이력 한 건을 나타내는 엔티티
// 반을 옮기면 기존 행을 지우지 않고 unassignedAt만 채우고, 새 행을 추가로 만듦

@Entity
@Table(name = "class_membership")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClassMembership {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "class_membership_id", nullable = false, updatable = false)
	private UUID classMembershipId;

	@Column(name = "class_id", nullable = false, updatable = false)
	private UUID classId;

	@Column(name = "cohort_member_id", nullable = false, updatable = false)
	private UUID cohortMemberId;

	// 배정된 사람을 사용자 기준으로 바로 조회하기 위한 컬럼 (cohort_member 조인 없이 조회용)
	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	// 어느 기관 소속인지. 다른 기관 데이터가 보이면 안 되니까 조회할 때 항상 이 값도 같이 확인합니다.
	@Column(name = "org_id", nullable = false, updatable = false)
	private UUID orgId;

	@Column(name = "assigned_at", nullable = false, updatable = false)
	private OffsetDateTime assignedAt;

	@Column(name = "unassigned_at")
	private OffsetDateTime unassignedAt;

	// 한 번의 일괄 배정 요청으로 만들어진 행들을 묶어서 추적하기 위한 식별자
	@Column(name = "assignment_batch_id")
	private String assignmentBatchId;

	@Column(name = "assigned_by")
	private UUID assignedBy;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Builder
	private ClassMembership(UUID classId, UUID cohortMemberId, UUID userId, UUID orgId,
							OffsetDateTime assignedAt, String assignmentBatchId, UUID assignedBy) {
		this.classId = classId;
		this.cohortMemberId = cohortMemberId;
		this.userId = userId;
		this.orgId = orgId;
		this.assignedAt = assignedAt;
		this.assignmentBatchId = assignmentBatchId;
		this.assignedBy = assignedBy;
	}

	// 배정 해제. 이미 해제된 배정을 또 해제해도 조용히 넘어가고(멱등),
	// 배정 시각보다 이른 시각으로 해제하려는 시도는 막는다
	public void unassign(OffsetDateTime unassignedAt) {
		if (this.unassignedAt != null) {
			return;
		}
		if (unassignedAt.isBefore(this.assignedAt)) {
			throw new IllegalArgumentException("배정 해제 시각이 배정 시각보다 이릅니다.");
		}
		this.unassignedAt = unassignedAt;
	}
}