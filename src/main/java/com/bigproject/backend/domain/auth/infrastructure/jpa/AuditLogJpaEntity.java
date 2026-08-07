package com.bigproject.backend.domain.auth.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "audit_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLogJpaEntity {
	@Id
	@Column(name = "audit_id", nullable = false, updatable = false)
	private UUID auditId;
}
