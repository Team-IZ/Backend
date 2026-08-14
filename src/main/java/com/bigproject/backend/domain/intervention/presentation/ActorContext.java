package com.bigproject.backend.domain.intervention.presentation;

import com.bigproject.backend.domain.intervention.domain.InterventionErrorCode;
import com.bigproject.backend.global.exception.ApiException;
import org.springframework.security.core.Authentication;

import java.util.UUID;

/**
 * 인증 정보에서 요청 주체를 꺼낸다.
 *
 * <p>두 컨트롤러가 같은 코드를 들고 있었다. 기관 ID를 토큰에서 꺼내는 것은 <b>보안 경계</b>라
 * — 클라이언트가 보낸 값을 쓰면 다른 기관 회차 ID를 넣어 호출할 수 있다 — 한 벌만 두고
 * 양쪽이 같은 규칙을 쓰게 한다.
 */
final class ActorContext {

	private ActorContext() {
	}

	/**
	 * 로그인한 사용자의 기관 ID.
	 *
	 * <p>{@code IllegalStateException} 대신 도메인 예외를 던진다 — 전자는 500 스택트레이스로
	 * 새어 나가고 어느 도메인에서 났는지도 로그에 남지 않는다.
	 */
	static UUID organizationId(Authentication authentication) {
		Object details = authentication.getDetails();
		if (!(details instanceof UUID organizationId)) {
			throw new ApiException(InterventionErrorCode.ORGANIZATION_CONTEXT_MISSING);
		}
		return organizationId;
	}
}
