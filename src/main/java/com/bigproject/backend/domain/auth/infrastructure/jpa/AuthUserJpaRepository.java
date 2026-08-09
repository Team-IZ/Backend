package com.bigproject.backend.domain.auth.infrastructure.jpa;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthUserJpaRepository extends JpaRepository<AuthUserJpaEntity, UUID> {
	@Query(value = """
			SELECT
				u.user_id AS "userId",
				u.org_id AS "organizationId",
				CAST(u.email AS text) AS "email",
				u.name AS "name",
				u.password_hash AS "passwordHash",
				u.status AS "status",
				u.is_email_verified AS "emailVerified",
				u.login_blocked_until AS "lockedUntil",
				u.password_changed_at AS "passwordChangedAt",
				r.code AS "roleCode",
				o.status AS "organizationStatus"
			FROM app_user u
			JOIN "role" r ON r.role_id = u.role_id
			LEFT JOIN organization o ON o.org_id = u.org_id
			WHERE u.normalized_email = :normalizedEmail
				AND u.deleted_at IS NULL
			""", nativeQuery = true)
	Optional<AuthUserProjection> findAuthUser(@Param("normalizedEmail") String normalizedEmail);

	@Query(value = """
			SELECT
				u.user_id AS "userId",
				u.org_id AS "organizationId",
				o.name AS "organizationName",
				CAST(u.email AS text) AS "email",
				u.name AS "name",
				u.normalized_email AS "normalizedEmail",
				u.password_hash AS "passwordHash",
				u.status AS "status",
				r.code AS "roleCode",
				ui.invitation_id AS "invitationId",
				ui.target_cohort_id AS "cohortId",
				c.name AS "cohortName"
			FROM app_user u
			JOIN "role" r ON r.role_id = u.role_id
			LEFT JOIN organization o ON o.org_id = u.org_id
			LEFT JOIN user_invitation ui ON ui.invitation_id = (
				SELECT latest_ui.invitation_id
				FROM user_invitation latest_ui
				WHERE latest_ui.target_email_normalized = u.normalized_email
					AND latest_ui.status IN ('PENDING', 'SENT', 'DELIVERY_FAILED', 'EXPIRED')
				ORDER BY latest_ui.invited_at DESC, latest_ui.created_at DESC
				LIMIT 1
			)
			LEFT JOIN cohort c ON c.cohort_id = ui.target_cohort_id
			WHERE u.normalized_email = :normalizedEmail
				AND u.deleted_at IS NULL
			""", nativeQuery = true)
	Optional<PasswordResetAccountProjection> findPasswordResetAccount(
			@Param("normalizedEmail") String normalizedEmail
	);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			SELECT u
			FROM AuthUserJpaEntity u
			WHERE u.userId = :userId
				AND (u.organizationId = :organizationId
					OR (u.organizationId IS NULL AND :organizationId IS NULL))
			""")
	Optional<AuthUserJpaEntity> lockUserContext(
			@Param("userId") UUID userId,
			@Param("organizationId") UUID organizationId
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			UPDATE app_user
			SET last_login_at = :lastLoginAt
			WHERE user_id = :userId
				AND deleted_at IS NULL
			""", nativeQuery = true)
	int updateLastLoginAt(@Param("userId") UUID userId, @Param("lastLoginAt") Instant lastLoginAt);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			UPDATE app_user
			SET name = :name,
				password_hash = :passwordHash,
				status = 'ACTIVE',
				is_email_verified = TRUE,
				email_verified_at = :activatedAt,
				failed_login_count = 0,
				login_blocked_until = NULL,
				password_changed_at = :activatedAt,
				updated_at = :activatedAt,
				row_version = row_version + 1
			WHERE user_id = :userId
				AND status = 'PENDING'
				AND deleted_at IS NULL
				AND row_version = :expectedRowVersion
			""", nativeQuery = true)
	int activate(
			@Param("userId") UUID userId,
			@Param("expectedRowVersion") int expectedRowVersion,
			@Param("name") String name,
			@Param("passwordHash") String passwordHash,
			@Param("activatedAt") Instant activatedAt
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			UPDATE app_user
			SET password_hash = :passwordHash,
				password_changed_at = :changedAt,
				failed_login_count = 0,
				login_blocked_until = NULL,
				updated_at = :changedAt,
				row_version = row_version + 1
			WHERE user_id = :userId
				AND status = 'ACTIVE'
				AND deleted_at IS NULL
			""", nativeQuery = true)
	int updatePassword(
			@Param("userId") UUID userId,
			@Param("passwordHash") String passwordHash,
			@Param("changedAt") Instant changedAt
	);

	interface AuthUserProjection {
		UUID getUserId();
		UUID getOrganizationId();
		String getEmail();
		String getName();
		String getPasswordHash();
		String getStatus();
		boolean getEmailVerified();
		Instant getLockedUntil();
		Instant getPasswordChangedAt();
		String getRoleCode();
		String getOrganizationStatus();
	}

	interface PasswordResetAccountProjection {
		UUID getUserId();
		UUID getOrganizationId();
		String getOrganizationName();
		String getEmail();
		String getName();
		String getNormalizedEmail();
		String getPasswordHash();
		String getStatus();
		String getRoleCode();
		UUID getInvitationId();
		UUID getCohortId();
		String getCohortName();
	}
}
