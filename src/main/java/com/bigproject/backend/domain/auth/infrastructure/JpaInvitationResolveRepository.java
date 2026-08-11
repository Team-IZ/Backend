package com.bigproject.backend.domain.auth.infrastructure;

import com.bigproject.backend.domain.auth.domain.InvitationResolveRepository;
import com.bigproject.backend.domain.auth.domain.InvitationState;
import com.bigproject.backend.domain.auth.infrastructure.jpa.OneTimeTokenJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaInvitationResolveRepository implements InvitationResolveRepository {
	private final OneTimeTokenJpaRepository repository;

	@Override
	public Optional<InvitationState> findStateByTokenHash(String tokenHash) {
		return repository.findInvitationState(tokenHash).map(InvitationStateMapper::toState);
	}
}
