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
}
