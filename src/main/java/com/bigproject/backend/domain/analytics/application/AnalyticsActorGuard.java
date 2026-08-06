package com.bigproject.backend.domain.analytics.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.member.application.EmailNormalizer;
import com.bigproject.backend.domain.member.domain.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

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
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증 사용자를 찾을 수 없습니다."));
		if (!ACTIVE.equals(actor.status()) || !actor.emailVerified()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "활성 사용자만 분석 정보를 조회할 수 있습니다.");
		}
		if (actor.role() != Role.SUPER_ADMIN && !ACTIVE.equals(actor.organizationStatus())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "활성 기관의 사용자만 분석 정보를 조회할 수 있습니다.");
		}
		if (actor.role() != Role.OPERATOR && actor.role() != Role.MANAGER) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, deniedMessage);
		}
		return actor;
	}

	public void requireSameOrganization(UUID cohortOrganizationId, AuthUser actor) {
		if (!cohortOrganizationId.equals(actor.organizationId())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 기관의 기수는 조회할 수 없습니다.");
		}
	}
}
