package com.bigproject.backend.domain.organization.application;

import com.bigproject.backend.domain.member.application.MemberInvitationService;
import com.bigproject.backend.domain.organization.domain.AccountInactivationReason;
import com.bigproject.backend.domain.organization.domain.OperatorAccountStatus;
import com.bigproject.backend.domain.organization.domain.OrganizationErrorCode;
import com.bigproject.backend.domain.organization.domain.OrganizationException;
import com.bigproject.backend.domain.organization.domain.OrganizationOperatorRepository;
import com.bigproject.backend.domain.organization.infrastructure.OrganizationRepository;
import com.bigproject.backend.global.security.CurrentUserResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 오퍼레이터 초대 취소.
 *
 * <p><b>이 테스트가 지키는 것은 "취소가 계정을 INACTIVE로 내린다"는 계약이다.</b>
 * 자리를 PENDING으로 남기면 재초대가 막힌다 — member 도메인의 재사용 조회가
 * {@code status='INACTIVE'}인 자리를 찾고, 못 찾으면 {@code app_user.normalized_email}
 * UNIQUE에 걸려 409 ALREADY_INVITED가 나기 때문이다. 실제로 그렇게 바꿨다가 재초대가 깨졌다.
 */
class OperatorInvitationCancelTest {

	private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
	private final OrganizationOperatorRepository operatorRepository = mock(OrganizationOperatorRepository.class);
	private final CurrentUserResolver currentUserResolver = mock(CurrentUserResolver.class);

	private final OperatorService service = new OperatorServiceImpl(
			organizationRepository,
			operatorRepository,
			mock(MemberInvitationService.class),
			currentUserResolver
	);

	private static final UUID ORG_ID = UUID.randomUUID();
	private static final UUID TOKEN_ID = UUID.randomUUID();
	private static final UUID MEMBER_ID = UUID.randomUUID();
	private static final UUID INVITATION_ID = UUID.randomUUID();
	private static final UUID ACTOR_ID = UUID.randomUUID();

	private void givenInvitation(UUID memberId, UUID invitationId) {
		when(organizationRepository.existsById(ORG_ID)).thenReturn(true);
		when(currentUserResolver.resolveCurrentMemberId()).thenReturn(ACTOR_ID);
		when(operatorRepository.findPendingInvitation(ORG_ID, TOKEN_ID))
				.thenReturn(Optional.of(new OrganizationOperatorRepository.PendingOperatorInvitation(
						TOKEN_ID, memberId, invitationId, "choi@green.com")));
		when(operatorRepository.findOperators(ORG_ID)).thenReturn(List.of());
	}

	@Test
	void 취소하면_계정을_INACTIVE로_내려_재초대_자리를_만든다() {
		givenInvitation(MEMBER_ID, INVITATION_ID);

		service.cancelInvitation(ORG_ID, TOKEN_ID);

		/*
		 * 이 호출이 빠지면 자리가 PENDING으로 남아 같은 주소로 다시 초대할 수 없다.
		 * 재초대 경로(FIND_REUSABLE_INVITED_USER)가 INACTIVE 자리를 찾기 때문이다.
		 */
		verify(operatorRepository).updateOperatorStatus(
				eq(MEMBER_ID),
				eq(OperatorAccountStatus.INACTIVE),
				eq(ACTOR_ID),
				eq(AccountInactivationReason.ADMIN_SUSPENDED),
				any());
	}

	@Test
	void 토큰과_원장도_함께_닫는다() {
		givenInvitation(MEMBER_ID, INVITATION_ID);

		service.cancelInvitation(ORG_ID, TOKEN_ID);

		// 토큰만 무효화하면 원장이 SENT로 남아 감사·통계가 발송된 초대로 계속 센다.
		verify(operatorRepository).invalidateInvitation(eq(TOKEN_ID), eq("INVITATION_CANCELLED"));
		verify(operatorRepository).cancelInvitationLedger(INVITATION_ID, ACTOR_ID);
	}

	@Test
	void 계정이_연결되지_않은_토큰은_상태를_건드리지_않는다() {
		givenInvitation(null, INVITATION_ID);

		service.cancelInvitation(ORG_ID, TOKEN_ID);

		verify(operatorRepository, never()).updateOperatorStatus(any(), any(), any(), any(), any());
		// 계정이 없어도 토큰·원장은 닫는다.
		verify(operatorRepository).invalidateInvitation(eq(TOKEN_ID), any());
	}

	@Test
	void 원장이_없는_토큰은_원장_마감을_건너뛴다() {
		givenInvitation(MEMBER_ID, null);

		service.cancelInvitation(ORG_ID, TOKEN_ID);

		verify(operatorRepository, never()).cancelInvitationLedger(any(), any());
		verify(operatorRepository).updateOperatorStatus(
				eq(MEMBER_ID), eq(OperatorAccountStatus.INACTIVE), any(), any(), any());
	}

	@Test
	void 취소할_초대가_없으면_404다() {
		when(organizationRepository.existsById(ORG_ID)).thenReturn(true);
		when(operatorRepository.findPendingInvitation(ORG_ID, TOKEN_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.cancelInvitation(ORG_ID, TOKEN_ID))
				.isInstanceOf(OrganizationException.class)
				.extracting(e -> ((OrganizationException) e).errorCode())
				.isEqualTo(OrganizationErrorCode.OPERATOR_INVITATION_NOT_FOUND);

		// 이미 취소된 초대를 두 번 취소해도 토큰·계정을 다시 건드리지 않는다.
		verify(operatorRepository, never()).invalidateInvitation(any(), any());
		verify(operatorRepository, never()).updateOperatorStatus(any(), any(), any(), any(), any());
	}
}
