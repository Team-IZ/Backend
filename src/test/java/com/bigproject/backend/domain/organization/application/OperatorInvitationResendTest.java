package com.bigproject.backend.domain.organization.application;

import com.bigproject.backend.domain.member.application.MemberInvitationService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 오퍼레이터 초대 재발송.
 *
 * <p><b>없는 토큰은 메일 경로에 닿기 전에 404로 끝나야 한다.</b> 기관 경계 검사를 건너뛰고
 * member 도메인에 그대로 넘기면 남의 기관 토큰을 건드릴 수 있고, 존재하지 않는 토큰이
 * 발송 단계까지 내려가 응답이 메일 서버 사정에 매달리게 된다.
 */
class OperatorInvitationResendTest {

	private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
	private final OrganizationOperatorRepository operatorRepository = mock(OrganizationOperatorRepository.class);
	private final MemberInvitationService memberInvitationService = mock(MemberInvitationService.class);

	private final OperatorService service = new OperatorServiceImpl(
			organizationRepository,
			operatorRepository,
			memberInvitationService,
			mock(CurrentUserResolver.class)
	);

	private static final UUID ORG_ID = UUID.randomUUID();
	private static final UUID TOKEN_ID = UUID.randomUUID();
	private static final String ACTOR_EMAIL = "admin@iz-get.com";

	@Test
	void 재발송할_초대가_없으면_404다() {
		when(organizationRepository.existsById(ORG_ID)).thenReturn(true);
		when(operatorRepository.findPendingInvitation(ORG_ID, TOKEN_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.resendInvitation(ORG_ID, TOKEN_ID, ACTOR_EMAIL, null))
				.isInstanceOf(OrganizationException.class)
				.extracting(e -> ((OrganizationException) e).errorCode())
				.isEqualTo(OrganizationErrorCode.OPERATOR_INVITATION_NOT_FOUND);

		// 메일 발송 경로에 아예 닿지 않는다 — 없는 토큰의 응답이 메일 서버 사정에 매달리면 안 된다.
		verify(memberInvitationService, never()).resendInvitation(any(), any(), any());
	}

	@Test
	void 기관의_대기_초대면_member_도메인에_넘긴다() {
		when(organizationRepository.existsById(ORG_ID)).thenReturn(true);
		when(operatorRepository.findPendingInvitation(ORG_ID, TOKEN_ID))
				.thenReturn(Optional.of(new OrganizationOperatorRepository.PendingOperatorInvitation(
						TOKEN_ID, UUID.randomUUID(), UUID.randomUUID(), "choi@green.com")));
		when(operatorRepository.findOperators(ORG_ID)).thenReturn(List.of());

		service.resendInvitation(ORG_ID, TOKEN_ID, ACTOR_EMAIL, "resend-op-001");

		verify(memberInvitationService).resendInvitation(TOKEN_ID, ACTOR_EMAIL, "resend-op-001");
	}
}
