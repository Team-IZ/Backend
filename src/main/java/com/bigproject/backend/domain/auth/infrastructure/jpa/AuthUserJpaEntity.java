package com.bigproject.backend.domain.auth.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthUserJpaEntity {
	@Id
	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "org_id", updatable = false)
	private UUID organizationId;

	@Column(name = "role_code", nullable = false, updatable = false)
	private String roleCode;

	// PostgreSQL 확장 타입 CITEXT를 명시해야 ddl-auto=validate가 VARCHAR로 오판하지 않는다.
	@Column(name = "email", nullable = false, updatable = false, columnDefinition = "citext")
	private String email;

	@Column(name = "normalized_email", nullable = false, updatable = false, length = 320)
	private String normalizedEmail;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "status", nullable = false)
	private String status;

	@Column(name = "is_email_verified", nullable = false)
	private boolean emailVerified;

	@Column(name = "login_blocked_until")
	private Instant loginBlockedUntil;

	@Column(name = "last_login_at")
	private Instant lastLoginAt;

	@Column(name = "password_changed_at")
	private Instant passwordChangedAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Version
	@Column(name = "row_version", nullable = false)
	private int rowVersion;
}
