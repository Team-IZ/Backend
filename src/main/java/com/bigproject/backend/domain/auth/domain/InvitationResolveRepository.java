package com.bigproject.backend.domain.auth.domain;

import java.time.Instant;
import java.util.Optional;

public interface InvitationResolveRepository {
	Optional<InvitationRecipient> findResolvableByTokenHash(String tokenHash, Instant resolvedAt);
}
