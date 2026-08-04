package com.bigproject.backend.domain.auth.infrastructure;

import com.bigproject.backend.domain.auth.domain.InvitationRecipient;
import com.bigproject.backend.domain.auth.domain.InvitationResolveRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.OneTimeTokenJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaInvitationResolveRepository implements InvitationResolveRepository {
	private final OneTimeTokenJpaRepository repository;

	@Override
	public Optional<InvitationRecipient> findResolvableByTokenHash(String tokenHash, Instant resolvedAt) {
		return repository.findResolvableInvitation(tokenHash, resolvedAt)
				.map(row -> new InvitationRecipient(row.getUserId(), row.getEmail()));
	}
}
