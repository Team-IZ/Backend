package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.AuthErrorCode;
import com.bigproject.backend.global.config.AllowedOriginPolicy;
import com.bigproject.backend.global.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginClientValidatorTest {
	private final LoginClientValidator validator = validatorFor(
			"http://localhost:5173,https://team-iz.github.io"
	);

	@Test
	void acceptsConfiguredOriginWithoutCheckingLoginPathOrRole() {
		validator.validateOrigin("http://localhost:5173");
		validator.validateOrigin("https://team-iz.github.io");
	}

	@Test
	void rejectsUnknownOrigin() {
		assertThatThrownBy(() ->
				validator.validateOrigin("http://localhost:5174")
		).isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(AuthErrorCode.LOGIN_ORIGIN_NOT_ALLOWED))
				.hasMessageContaining("허용되지 않은 클라이언트");
	}

	/**
	 * 요청서 R2의 전수 확인 목록을 그대로 테스트로 남긴다. 5173만 열려 있으면 Vite가 포트를 말없이
	 * 밀거나(5174·5175) 127.0.0.1로 열거나 vite preview(4173)를 쓸 때마다 로그인이 막히고,
	 * 원인이 코드가 아니라 포트라 찾는 데 오래 걸린다.
	 */
	@Test
	void 배포_도메인과_로컬_개발_오리진_전부를_허용한다() {
		LoginClientValidator deployed = validatorFor(
				"https://frontend-eight-neon-73.vercel.app,http://localhost:5173,http://localhost:5174,"
						+ "http://localhost:5175,http://127.0.0.1:5173,http://localhost:4173"
		);

		for (String origin : new String[]{
				"https://frontend-eight-neon-73.vercel.app",
				"http://localhost:5173",
				"http://localhost:5174",
				"http://localhost:5175",
				// 브라우저에게 localhost와 127.0.0.1은 다른 오리진이다. 주소를 손으로 치면 갈린다.
				"http://127.0.0.1:5173",
				// vite preview. 배포 빌드를 로컬에서 확인할 때 쓴다.
				"http://localhost:4173"
		}) {
			assertThatCode(() -> deployed.validateOrigin(origin))
					.withFailMessage("허용되어야 하는 Origin이 거절됐다: %s", origin)
					.doesNotThrowAnyException();
		}
	}

	@Test
	void 끝에_슬래시가_붙은_설정값도_같은_오리진으로_본다() {
		// 브라우저가 보내는 Origin에는 경로가 붙지 않는다. 설정 실수 하나로 CORS와 판정이 갈리면
		// "CORS는 통과했는데 403"이 되어 원인을 찾기 어렵다.
		assertThatCode(() -> validatorFor("https://app.example.com/").validateOrigin("https://app.example.com"))
				.doesNotThrowAnyException();
	}

	@Test
	void 와일드카드는_켜야_동작한다() {
		String previewOrigin = "https://frontend-abc123-team-iz.vercel.app";

		assertThatThrownBy(() -> validatorFor("https://*.vercel.app").validateOrigin(previewOrigin))
				.isInstanceOf(ApiException.class);
		assertThatCode(() -> new LoginClientValidator(new AllowedOriginPolicy("https://*.vercel.app", true))
				.validateOrigin(previewOrigin))
				.doesNotThrowAnyException();
	}

	@Test
	void Origin_헤더가_없으면_거절한다() {
		assertThatThrownBy(() -> validator.validateOrigin(null)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> validator.validateOrigin("  ")).isInstanceOf(ApiException.class);
	}

	private static LoginClientValidator validatorFor(String allowedOrigins) {
		return new LoginClientValidator(new AllowedOriginPolicy(allowedOrigins, false));
	}
}
