package com.bigproject.backend.domain.classroom.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

// 매니저가 기수 전체(COHORT) 또는 특정 반(CLASS)을 담당하는 배정 이력 한 건을 나타내는 엔티티
// 담당이 바뀌면 기존 행을 지우지 않고 unassignedAt만 채우고, 새 행을 추가로 만듦

@Entity
@Table(name = "manager_assignment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ManagerAssignment {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "assignment_id", nullable = false, updatable = false)
	private UUID assignmentId;

	@Column(name = "manager_user_id", nullable = false, updatable = false)
	private UUID managerUserId;

	// 어느 기관 소속인지. 다른 기관 데이터가 보이면 안 되니까 조회할 때 항상 이 값도 같이 확인합니다.
	@Column(name = "org_id", nullable = false, updatable = false)
	private UUID orgId;

	@Column(name = "class_id", nullable = false, updatable = false)
	private UUID classId;

	@Column(name = "assigned_at", nullable = false, updatable = false)
	private OffsetDateTime assignedAt;

	@Column(name = "unassigned_at")
	private OffsetDateTime unassignedAt;

	// CHECK 제약이 없는 카탈로그형 코드라 String으로 처리
	@Column(name = "status", nullable = false, length = 30)
	private String status;

	@Column(name = "assigned_by", nullable = false, updatable = false)
	private UUID assignedBy;

	@Column(name = "unassigned_by")
	private UUID unassignedBy;

	@Column(name = "unassigned_reason", length = 50)
	private String unassignedReason;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Builder
	private ManagerAssignment(UUID managerUserId, UUID orgId, UUID classId,
			OffsetDateTime assignedAt, String status, UUID assignedBy) {
		this.managerUserId = managerUserId;
		this.orgId = orgId;
		this.classId = classId;
		this.assignedAt = assignedAt;
		this.status = status;
		this.assignedBy = assignedBy;
	}

	public void unassign(OffsetDateTime unassignedAt, UUID unassignedBy, String unassignedReason) {
		this.unassignedAt = unassignedAt;
		this.unassignedBy = unassignedBy;
		this.unassignedReason = unassignedReason;
		this.status = "ENDED";
	}
}
