package com.bigproject.backend.domain.auth.domain;

import java.util.UUID;

public record RefreshTokenLineage(
		UUID parentTokenId,
		UUID tokenFamilyId
) {
}
