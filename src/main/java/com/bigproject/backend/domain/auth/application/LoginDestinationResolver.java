package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.LoginCohortRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class LoginDestinationResolver {
	private static final String COHORTS_PATH = "/cohorts";

	private final LoginCohortRepository loginCohortRepository;

	public String resolveRedirectPath(AuthUser user) {
		return switch (user.role()) {
			case SUPER_ADMIN -> "/admin/orgs";
			case OPERATOR -> cohortPath(
					loginCohortRepository.findLatestNameForOrganization(user.organizationId())
			);
			case MANAGER -> cohortPath(
					loginCohortRepository.findLatestAssignedNameForManager(
							user.organizationId(),
							user.userId(),
							Instant.now()
					)
			);
			case TRAINEE -> "/home";
		};
	}

	private String cohortPath(Optional<String> cohortName) {
		return cohortName
				.map(name -> COHORTS_PATH + "/" + UriUtils.encodePathSegment(name, StandardCharsets.UTF_8))
				.orElse(COHORTS_PATH);
	}
}
