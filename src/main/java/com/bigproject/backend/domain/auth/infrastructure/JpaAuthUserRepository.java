package com.bigproject.backend.domain.auth.infrastructure;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.AuthUserJpaRepository;
import com.bigproject.backend.domain.member.domain.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JpaAuthUserRepository implements AuthUserRepository {
	private final AuthUserJpaRepository repository;

	@Override
	public Optional<AuthUser> findByNormalizedEmail(String normalizedEmail) {
		return repository.findAuthUser(normalizedEmail).map(row -> new AuthUser(
				row.getUserId(),
				row.getOrganizationId(),
				row.getEmail(),
				row.getName(),
				row.getPasswordHash(),
				row.getStatus(),
				row.getEmailVerified(),
				row.getLockedUntil(),
				row.getPasswordChangedAt(),
				Role.valueOf(row.getRoleCode()),
				row.getOrganizationStatus()
		));
	}

	@Override
	public void updateLastLoginAt(UUID userId, Instant lastLoginAt) {
		if (repository.updateLastLoginAt(userId, lastLoginAt) != 1) {
			throw new IllegalStateException("로그인 사용자의 최근 로그인 시각을 갱신할 수 없습니다.");
		}
	}
}
