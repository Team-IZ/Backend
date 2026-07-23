package com.bigproject.backend.domain.auth.domain;

import java.util.UUID;

public record InvitationRecipient(
		UUID userId,
		String email
) {
}
