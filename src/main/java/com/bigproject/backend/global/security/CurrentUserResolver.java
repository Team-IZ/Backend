package com.bigproject.backend.global.security;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.UUID;

/**
 * 인증된 요청의 SecurityContext(이메일)로 app_user.user_id(UUID)를 조회한다.
 * JWT에는 이메일만 있고 memberId 클레임이 없어서, organization/operations처럼 요청자 UUID(created_by 등)가
 * 필요한 도메인은 이 컴포넌트로 실제 UUID를 얻어야 한다.
 */
@Component
@RequiredArgsConstructor
public class CurrentUserResolver {

	private final AuthUserRepository authUserRepository;

	public UUID resolveCurrentMemberId() {
		return resolveCurrentUser().userId();
	}

	/**
	 * 인증된 사용자 전체 정보(역할, 소속 기관 포함)를 조회한다.
	 * 슈퍼어드민이 아닌 역할이 기관 스코프 API를 호출할 때 "자기 기관인지" 검증하려면 organizationId가 필요하다.
	 */
	public AuthUser resolveCurrentUser() {
		String email = SecurityContextHolder.getContext().getAuthentication().getName();
		String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
		return authUserRepository.findByNormalizedEmail(normalizedEmail)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증된 사용자를 찾을 수 없습니다."));
	}
}
