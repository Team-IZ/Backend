package com.bigproject.backend.domain.auth.domain;

import java.util.UUID;

public record RefreshTokenSession(
		UUID tokenId,
		UUID userId
) {
}
