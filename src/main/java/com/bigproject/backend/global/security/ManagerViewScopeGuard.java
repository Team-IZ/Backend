package com.bigproject.backend.global.security;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.member.application.EmailNormalizer;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.global.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ManagerViewScopeGuard {
	private static final String ACTIVE = "ACTIVE";

	private final AuthUserRepository authUserRepository;
	private final JdbcTemplate jdbcTemplate;

	public ManagerActor requireManager(String email) {
		AuthUser user = authUserRepository.findByNormalizedEmail(EmailNormalizer.normalize(email))
				.orElseThrow(() -> new ApiException(ManagerViewAccessErrorCode.MANAGER_VIEWER_NOT_FOUND));
		if (!ACTIVE.equals(user.status()) || !user.emailVerified()
				|| !ACTIVE.equals(user.organizationStatus())) {
			throw new ApiException(ManagerViewAccessErrorCode.MANAGER_VIEWER_NOT_ACTIVE);
		}
		if (user.role() != Role.MANAGER) {
			throw new ApiException(ManagerViewAccessErrorCode.MANAGER_ROLE_REQUIRED);
		}
		return new ManagerActor(user.userId(), user.organizationId());
	}

	public ManagerActor requireCohort(String email, UUID cohortId) {
		ManagerActor actor = requireManager(email);
		Boolean allowed = jdbcTemplate.queryForObject("""
				SELECT EXISTS (
				  SELECT 1
				  FROM cohort co
				  JOIN class c ON c.cohort_id = co.cohort_id AND c.deleted_at IS NULL
				  JOIN manager_assignment ma ON ma.class_id = c.class_id
				  WHERE co.cohort_id = ? AND co.org_id = ? AND co.deleted_at IS NULL
				    AND ma.manager_user_id = ? AND ma.status = 'ACTIVE' AND ma.unassigned_at IS NULL
				)
				""", Boolean.class, cohortId, actor.organizationId(), actor.userId());
		if (!Boolean.TRUE.equals(allowed)) {
			throw new ApiException(ManagerViewAccessErrorCode.MANAGER_SCOPE_NOT_FOUND);
		}
		return actor;
	}

	public record ManagerActor(UUID userId, UUID organizationId) {
	}
}
