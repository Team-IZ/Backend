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
		if (!managesCohort(actor.userId(), actor.organizationId(), cohortId)) {
			throw new ApiException(ManagerViewAccessErrorCode.MANAGER_SCOPE_NOT_FOUND);
		}
		return actor;
	}

	/**
	 * 이 매니저가 그 기수에서 <b>반을 하나라도</b> 맡고 있는가.
	 *
	 * <p>{@link #requireCohort}와 갈라 둔 이유는 <b>오퍼레이터도 부르는 조회</b>가 있기 때문이다
	 * (30차 R3 — 반별 진행). 그런 자리는 역할을 먼저 가른 뒤 매니저 갈래에서만 이 판정을 쓴다.
	 * {@code requireCohort}는 매니저 전용이라 그대로 쓸 수 없고, 그렇다고 판정을 한 벌 더 적으면
	 * 「담당 기수」의 정의가 둘이 된다.
	 */
	public boolean managesCohort(UUID managerUserId, UUID organizationId, UUID cohortId) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
				SELECT EXISTS (
				  SELECT 1
				  FROM cohort co
				  JOIN class c ON c.cohort_id = co.cohort_id AND c.deleted_at IS NULL
				  JOIN manager_assignment ma ON ma.class_id = c.class_id
				  WHERE co.cohort_id = ? AND co.org_id = ? AND co.deleted_at IS NULL
				    AND ma.manager_user_id = ? AND ma.status = 'ACTIVE' AND ma.unassigned_at IS NULL
				)
				""", Boolean.class, cohortId, organizationId, managerUserId));
	}

	public record ManagerActor(UUID userId, UUID organizationId) {
	}
}
