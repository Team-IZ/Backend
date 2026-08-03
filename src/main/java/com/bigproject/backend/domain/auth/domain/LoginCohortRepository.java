package com.bigproject.backend.domain.auth.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface LoginCohortRepository {
	Optional<String> findLatestNameForOrganization(UUID organizationId);

	Optional<String> findLatestAssignedNameForManager(UUID organizationId, UUID managerUserId, Instant asOf);
}
