package com.bigproject.backend.domain.auth.domain;

public record TokenRequestMetadata(
		String ipAddress,
		String userAgent
) {
}
