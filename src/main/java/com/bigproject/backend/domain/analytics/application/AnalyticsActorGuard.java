package com.bigproject.backend.domain.analytics.application;

import com.bigproject.backend.domain.analytics.domain.AnalyticsErrorCode;
import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.member.application.EmailNormalizer;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.global.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 분석 화면 공통 접근 규칙.
 *
 * 계정 활성·이메일 인증·기관 활성·역할·기수 소유 기관 확인은 분석 엔드포인트마다 같으므로
 * 한 곳에 모은다. 서비스마다 복사하면 규칙이 조용히 갈라진다.
 */
@Component
@RequiredArgsConstructor
public class AnalyticsActorGuard {
	private static final String ACTIVE = "ACTIVE";

	private final AuthUserRepository authUserRepository;

	/**
	 * 오퍼레이터·매니저만 통과시킨다. deniedMessage는 화면마다 다른 안내 문구다.
	 */
	public AuthUser operatorOrManager(String actorEmail, String deniedMessage) {
		AuthUser actor = authUserRepository.findByNormalizedEmail(EmailNormalizer.normalize(actorEmail))
				.orElseThrow(() -> new ApiException(AnalyticsErrorCode.ANALYTICS_VIEWER_NOT_FOUND));
		if (!ACTIVE.equals(actor.status()) || !actor.emailVerified()) {
			throw new ApiException(AnalyticsErrorCode.ANALYTICS_VIEWER_NOT_ACTIVE);
		}
		if (actor.role() != Role.SUPER_ADMIN && !ACTIVE.equals(actor.organizationStatus())) {
			throw new ApiException(AnalyticsErrorCode.ANALYTICS_ORGANIZATION_NOT_ACTIVE);
		}
		if (actor.role() != Role.OPERATOR && actor.role() != Role.MANAGER) {
			throw new ApiException(AnalyticsErrorCode.ANALYTICS_ROLE_NOT_ALLOWED, deniedMessage);
		}
		return actor;
	}

	public void requireSameOrganization(UUID cohortOrganizationId, AuthUser actor) {
		if (!cohortOrganizationId.equals(actor.organizationId())) {
			throw new ApiException(AnalyticsErrorCode.ANALYTICS_COHORT_CROSS_ORGANIZATION);
		}
	}
}
