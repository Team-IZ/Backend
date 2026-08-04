package com.bigproject.backend.domain.auth.infrastructure;

import com.bigproject.backend.domain.auth.domain.LoginCohortRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.LoginCohortJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JpaLoginCohortRepository implements LoginCohortRepository {
	private final LoginCohortJpaRepository repository;

	@Override
	public Optional<String> findLatestNameForOrganization(UUID organizationId) {
		return repository.findLatestName(organizationId).stream().findFirst();
	}

	@Override
	public Optional<String> findLatestAssignedNameForManager(
			UUID organizationId,
			UUID managerUserId,
			Instant asOf
	) {
		return repository.findLatestAssignedName(organizationId, managerUserId, asOf).stream().findFirst();
	}
}
