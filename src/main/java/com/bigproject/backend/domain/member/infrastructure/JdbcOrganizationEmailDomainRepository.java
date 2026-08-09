package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.domain.OrganizationEmailDomainRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcOrganizationEmailDomainRepository implements OrganizationEmailDomainRepository {

	private final JdbcTemplate jdbcTemplate;

	@Override
	public Optional<String> findEmailDomain(UUID organizationId) {
		if (organizationId == null) {
			// 슈퍼어드민은 소속 기관이 없다. 예외가 아니라 정상 경로다.
			return Optional.empty();
		}
		// 삭제된 기관은 보지 않는다 — 그 기관 계정은 로그인할 수 없으므로 도메인을 돌려줄 이유가 없다.
		List<String> found = jdbcTemplate.queryForList(
				"SELECT email_domain FROM organization WHERE org_id = ? AND deleted_at IS NULL",
				String.class, organizationId);
		return found.stream().findFirst().filter(domain -> domain != null && !domain.isBlank());
	}
}
