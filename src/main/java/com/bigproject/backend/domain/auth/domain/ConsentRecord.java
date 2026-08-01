package com.bigproject.backend.domain.auth.domain;

import java.time.Instant;
import java.util.UUID;

public record ConsentRecord(
		UUID consentId,
		UUID organizationId,
		UUID userId,
		ConsentCode consentCode,
		int policyVersion,
		boolean agreed,
		Instant capturedAt,
		String captureChannel,
		String sourceIp,
		String userAgent,
		String locale,
		String evidenceHash
) {
}
