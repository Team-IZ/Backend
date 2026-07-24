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
		String email = SecurityContextHolder.getContext().getAuthentication().getName();
		String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
		return authUserRepository.findByNormalizedEmail(normalizedEmail)
				.map(AuthUser::userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증된 사용자를 찾을 수 없습니다."));
	}
}
