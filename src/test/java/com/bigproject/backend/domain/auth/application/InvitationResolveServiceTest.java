package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.AuthErrorCode;
import com.bigproject.backend.domain.auth.domain.InvitationResolveRepository;
import com.bigproject.backend.domain.auth.presentation.dto.InvitationResolveRequest;
import com.bigproject.backend.domain.member.application.OneTimeTokenHasher;
import com.bigproject.backend.domain.member.domain.InvitationPurpose;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.global.exception.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static com.bigproject.backend.domain.auth.application.InvitationStateFixture.EMAIL;
import static com.bigproject.backend.domain.auth.application.InvitationStateFixture.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvitationResolveServiceTest {
	private final InvitationResolveRepository repository = mock(InvitationResolveRepository.class);
	private final OneTimeTokenHasher tokenHasher = new OneTimeTokenHasher();
	private final InvitationResolveService service = new InvitationResolveService(repository, tokenHasher);

	@Test
	void resolvesPendingUserFromValidInvitationToken() throws Exception {
		stub(InvitationStateFixture.valid());

		var response = service.resolve(new InvitationResolveRequest(" raw-token "));

		assertThat(response.userId()).isEqualTo(USER_ID);
		assertThat(response.email()).isEqualTo(EMAIL);
		assertThat(response.role()).isEqualTo(Role.TRAINEE);
		String json = new ObjectMapper().writeValueAsString(response);
		assertThat(json).contains("\"user_id\":\"" + USER_ID + "\"");
		assertThat(json).doesNotContain("\"userId\"");
		assertThat(json).contains("\"role\":\"TRAINEE\"");
	}

	@Test
	void reportsExpiredInvitationSeparatelyFromInvalidOne() {
		stub(InvitationStateFixture.valid().withExpiresAt(Instant.now().minus(Duration.ofHours(1))));

		assertThatErrorCodeIs(AuthErrorCode.INVITATION_EXPIRED);
	}

	/** 재발송으로 교체된 이전 링크는 만료로 안내한다 — 더 새 메일이 이미 도착해 있기 때문이다. */
	@Test
	void reportsReplacedTokenAsExpired() {
		stub(InvitationStateFixture.valid().withInvalidated(Instant.now(), "REPLACED"));

		assertThatErrorCodeIs(AuthErrorCode.INVITATION_EXPIRED);
	}

	@Test
	void reportsSupersededTokenAsExpired() {
		stub(InvitationStateFixture.valid().withCurrentToken(false));

		assertThatErrorCodeIs(AuthErrorCode.INVITATION_EXPIRED);
	}

	@Test
	void reportsAlreadyAcceptedInvitation() {
		stub(InvitationStateFixture.valid().withUsedAt(Instant.now()));

		assertThatErrorCodeIs(AuthErrorCode.INVITATION_ALREADY_ACCEPTED);
	}

	@Test
	void reportsAlreadyActivatedAccountAsAccepted() {
		stub(InvitationStateFixture.valid().withUserStatus("ACTIVE"));

		assertThatErrorCodeIs(AuthErrorCode.INVITATION_ALREADY_ACCEPTED);
	}

	/**
	 * 수락된 초대는 시간이 지나면 만료 조건에도 걸린다. 그때 맞는 안내는 재발송이 아니라 로그인이므로
	 * 이미 수락을 먼저 판정한다 — 판정 순서가 곧 프론트와의 계약이다.
	 */
	@Test
	void prefersAlreadyAcceptedOverExpiredWhenBothApply() {
		stub(InvitationStateFixture.valid()
				.withUsedAt(Instant.now().minus(Duration.ofDays(10)))
				.withExpiresAt(Instant.now().minus(Duration.ofDays(3))));

		assertThatErrorCodeIs(AuthErrorCode.INVITATION_ALREADY_ACCEPTED);
	}

	@Test
	void reportsTraineeMissingFromRoster() {
		stub(InvitationStateFixture.valid().withOnRoster(false));

		assertThatErrorCodeIs(AuthErrorCode.INVITATION_NOT_IN_ROSTER);
	}

	@Test
	void reportsCancelledTraineeInvitationAsMissingFromRoster() {
		stub(InvitationStateFixture.valid().withInvitationStatus("CANCELLED"));

		assertThatErrorCodeIs(AuthErrorCode.INVITATION_NOT_IN_ROSTER);
	}

	/** 명단 검사는 교육생만이다. 매니저 초대가 취소된 것은 명단 문제가 아니라 무효다. */
	@Test
	void reportsCancelledStaffInvitationAsInvalid() {
		stub(InvitationStateFixture.staff(Role.MANAGER).withInvitationStatus("CANCELLED"));

		assertThatErrorCodeIs(AuthErrorCode.INVITATION_INVALID);
	}

	@Test
	void reportsSuspendedOrganizationAsInvalid() {
		stub(InvitationStateFixture.valid().withOrganizationStatus("SUSPENDED"));

		assertThatErrorCodeIs(AuthErrorCode.INVITATION_INVALID);
	}

	@Test
	void reportsEmailMismatchAsInvalid() {
		stub(InvitationStateFixture.valid().withEmailMatched(false));

		assertThatErrorCodeIs(AuthErrorCode.INVITATION_INVALID);
	}

	@Test
	void reportsUnknownTokenAsInvalid() {
		when(repository.findStateByTokenHash(eq(tokenHasher.hash("raw-token")))).thenReturn(Optional.empty());

		assertThatErrorCodeIs(AuthErrorCode.INVITATION_INVALID);
	}

	/** 비밀번호 재설정 토큰으로 초대를 해석하려는 시도는 상태를 알려 주지 않고 무효로 막는다. */
	@Test
	void reportsNonInvitationPurposeAsInvalid() {
		stub(InvitationStateFixture.valid().withPurpose(InvitationPurpose.PASSWORD_RESET));

		assertThatErrorCodeIs(AuthErrorCode.INVITATION_INVALID);
	}

	/** 슈퍼어드민 초대는 기관이 없다. 기관 검사가 NULL 에 걸려 막히면 안 된다. */
	@Test
	void resolvesSuperAdminInvitationWithoutOrganization() {
		stub(InvitationStateFixture.staff(Role.SUPER_ADMIN)
				.withPurpose(InvitationPurpose.INVITE_SUPER_ADMIN)
				.withoutOrganization());

		assertThat(service.resolve(new InvitationResolveRequest("raw-token")).role())
				.isEqualTo(Role.SUPER_ADMIN);
	}

	private void assertThatErrorCodeIs(AuthErrorCode expected) {
		assertThatThrownBy(() -> service.resolve(new InvitationResolveRequest("raw-token")))
				.isInstanceOfSatisfying(ApiException.class,
						exception -> assertThat(exception.errorCode()).isEqualTo(expected));
	}

	private void stub(InvitationStateFixture fixture) {
		when(repository.findStateByTokenHash(eq(tokenHasher.hash("raw-token"))))
				.thenReturn(Optional.of(fixture.build()));
	}
}
