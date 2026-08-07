package com.bigproject.backend.domain.member.domain;

import java.util.UUID;

public record InvitationContext(
		UUID organizationId,
		String organizationName,
		UUID cohortId,
		String cohortName
) {
	public static InvitationContext organization(UUID organizationId, String organizationName) {
		return new InvitationContext(organizationId, organizationName, null, null);
	}

	/**
	 * 기관에 속하지 않는 초대(슈퍼어드민)의 컨텍스트.
	 *
	 * <p>v07에서 {@code user_invitation.org_id}가 NULL 허용으로 바뀌면서 가능해졌다.
	 * {@code app_user.org_id}·{@code one_time_token.org_id}는 그 전부터 NULL을 허용하고 있었다 —
	 * 슈퍼어드민은 어느 기관에도 속하지 않기 때문이다.
	 */
	public static InvitationContext platform() {
		return new InvitationContext(null, null, null, null);
	}

	/** 기관 소속 초대인지. false면 슈퍼어드민 초대다. */
	public boolean hasOrganization() {
		return organizationId != null;
	}
}
