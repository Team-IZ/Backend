package com.bigproject.backend.domain.auth.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
		재발급된 액세스 토큰. **역할·이름은 담기지 않는다** — 새로고침 후 세션을 복원할 때는
		이 호출 뒤에 `GET /members/me`를 부른다.""")
public record RefreshTokenResponse(
		String accessToken,
		@Schema(
				description = "만료까지 남은 시간. **단위는 밀리초(ms)**다 — 1시간이면 3600000이다. "
						+ "만료 시각은 `Date.now() + accessTokenExpiresIn`이다.",
				example = "3600000"
		)
		long accessTokenExpiresIn
) {
}
