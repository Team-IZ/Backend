package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.presentation.dto.MemberProfileResponse;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.CurrentUserResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemberProfileServiceTest {
	private final CurrentUserResolver currentUserResolver = mock(CurrentUserResolver.class);
	private final MemberProfileService service = new MemberProfileService(currentUserResolver);

	@Test
	void 로그인_응답과_같은_값을_서버에서_다시_읽어_준다() {
		UUID memberId = UUID.randomUUID();
		UUID organizationId = UUID.randomUUID();
		when(currentUserResolver.resolveCurrentUser())
				.thenReturn(user(memberId, organizationId, Role.MANAGER, "ACTIVE"));

		MemberProfileResponse response = service.currentMember();

		assertThat(response.memberId()).isEqualTo(memberId);
		assertThat(response.email()).isEqualTo("lead@example.com");
		assertThat(response.name()).isEqualTo("홍길동");
		assertThat(response.role()).isEqualTo(Role.MANAGER);
		assertThat(response.organizationId()).isEqualTo(organizationId);
		assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);
	}

	@Test
	void 슈퍼어드민은_기관이_없다() {
		when(currentUserResolver.resolveCurrentUser())
				.thenReturn(user(UUID.randomUUID(), null, Role.SUPER_ADMIN, "ACTIVE"));

		assertThat(service.currentMember().organizationId()).isNull();
	}

	/**
	 * 세션 도중에 정지되면 액세스 토큰은 만료 전까지 계속 통과한다. 화면이 그 사이에 알아채려면
	 * 이 값이 서버 진실이어야 한다 — 로그인 때 받아 저장해 둔 값으로는 알 수 없다.
	 */
	@Test
	void 정지된_계정은_INACTIVE로_내려간다() {
		when(currentUserResolver.resolveCurrentUser())
				.thenReturn(user(UUID.randomUUID(), UUID.randomUUID(), Role.TRAINEE, "INACTIVE"));

		assertThat(service.currentMember().status()).isEqualTo(AccountStatus.INACTIVE);
	}

	/** DB CHECK 값은 PENDING이고 member 도메인은 그 상태를 INVITED로 표현한다(InviteManagerResponse와 같은 규칙). */
	@Test
	void DB의_PENDING을_INVITED로_옮겨_적는다() {
		when(currentUserResolver.resolveCurrentUser())
				.thenReturn(user(UUID.randomUUID(), UUID.randomUUID(), Role.TRAINEE, "PENDING"));

		assertThat(service.currentMember().status()).isEqualTo(AccountStatus.INVITED);
	}

	/**
	 * 그냥 두면 응답 코드가 HTTP 상태 이름(UNAUTHORIZED)이 되는데, 스펙의 코드 추출기가
	 * 밑줄 있는 토큰만 보기 때문에 그 코드는 문서의 목록에 잡히지 않는다 — 프론트가 분기할 수 없다.
	 */
	@Test
	void 계정이_사라졌으면_이름_있는_코드로_거절한다() {
		when(currentUserResolver.resolveCurrentUser())
				.thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증된 사용자를 찾을 수 없습니다."));

		assertThatThrownBy(service::currentMember)
				.isInstanceOfSatisfying(ApiException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
					assertThat(exception.errorCode().status().value()).isEqualTo(401);
				});
	}

	private AuthUser user(UUID memberId, UUID organizationId, Role role, String status) {
		return new AuthUser(
				memberId,
				organizationId,
				"lead@example.com",
				"홍길동",
				"hash",
				status,
				true,
				null,
				role,
				organizationId == null ? null : "ACTIVE"
		);
	}
}
