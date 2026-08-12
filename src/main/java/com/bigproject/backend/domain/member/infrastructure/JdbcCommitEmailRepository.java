package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.domain.CommitEmail;
import com.bigproject.backend.domain.member.domain.CommitEmailRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcCommitEmailRepository implements CommitEmailRepository {
	private static final String FIND_COMMIT_EMAIL = """
			SELECT commit_email,
			       commit_email_status,
			       commit_email_verification_method,
			       commit_email_verified_at,
			       commit_email_updated_at
			FROM app_user
			WHERE user_id = ?
				AND deleted_at IS NULL
			""";
	/*
	 * ck_app_user_commit_email_updated_at이 커밋 이메일 7개 컬럼을 all-or-nothing으로 묶고,
	 * ck_app_user_commit_email_verified_at이 status='VERIFIED' ⟺ verified_at NOT NULL을 강제한다.
	 * 이미 VERIFIED였던 사용자가 주소를 바꾸면 status가 PENDING으로 내려가므로 검증 흔적 3개를
	 * 함께 비우지 않으면 두 번째 CHECK를 위반한다.
	 */
	private static final String UPDATE_COMMIT_EMAIL = """
			UPDATE app_user
			SET commit_email = ?,
				commit_email_normalized = ?,
				commit_email_status = 'PENDING',
				commit_email_updated_at = ?,
				commit_email_verification_method = NULL,
				commit_email_verified_by = NULL,
				commit_email_verified_at = NULL,
				updated_at = ?,
				row_version = row_version + 1
			WHERE user_id = ?
				AND deleted_at IS NULL
			""";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public Optional<CommitEmail> findByUserId(UUID userId) {
		return jdbcTemplate.query(
				FIND_COMMIT_EMAIL,
				(rs, rowNum) -> new CommitEmail(
						rs.getString("commit_email"),
						rs.getString("commit_email_status"),
						rs.getString("commit_email_verification_method"),
						toInstant(rs.getTimestamp("commit_email_verified_at")),
						toInstant(rs.getTimestamp("commit_email_updated_at"))
				),
				userId
		).stream().findFirst();
	}

	@Override
	public int updateCommitEmail(UUID userId, String commitEmail, String normalizedCommitEmail, Instant updatedAt) {
		Timestamp timestamp = Timestamp.from(updatedAt);
		return jdbcTemplate.update(
				UPDATE_COMMIT_EMAIL,
				commitEmail,
				normalizedCommitEmail,
				timestamp,
				timestamp,
				userId
		);
	}

	private static Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}
}
