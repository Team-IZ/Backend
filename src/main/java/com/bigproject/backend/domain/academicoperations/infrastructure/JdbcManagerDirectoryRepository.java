package com.bigproject.backend.domain.academicoperations.infrastructure;

import com.bigproject.backend.domain.academicoperations.domain.ManagerDirectoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class JdbcManagerDirectoryRepository implements ManagerDirectoryRepository {

	private final JdbcTemplate jdbcTemplate;

	@Override
	public List<ManagerProfile> findProfiles(UUID orgId, Collection<UUID> managerUserIds) {
		// 빈 목록을 그대로 SQL로 만들면 IN ()이 되어 문법 오류가 난다. 담당자가 없는 반이 흔하므로
		// 예외가 아니라 정상 경로다.
		List<UUID> distinctIds = managerUserIds == null
				? List.of()
				: managerUserIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
		if (distinctIds.isEmpty()) {
			return List.of();
		}

		String placeholders = distinctIds.stream().map(id -> "?").collect(Collectors.joining(", "));
		String sql = """
				SELECT u.user_id, u.name, u.email
				FROM app_user u
				WHERE u.deleted_at IS NULL
					AND u.org_id = ?
					AND u.user_id IN (%s)
				""".formatted(placeholders);

		List<Object> args = new ArrayList<>();
		args.add(orgId);
		args.addAll(distinctIds);

		return jdbcTemplate.query(
				sql,
				(ResultSet rs, int rowNum) -> new ManagerProfile(
						rs.getObject("user_id", UUID.class),
						rs.getString("name"),
						rs.getString("email")),
				args.toArray());
	}
}
