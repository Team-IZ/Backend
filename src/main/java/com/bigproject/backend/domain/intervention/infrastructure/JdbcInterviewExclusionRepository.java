package com.bigproject.backend.domain.intervention.infrastructure;

import com.bigproject.backend.domain.intervention.domain.InterviewExclusionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcInterviewExclusionRepository implements InterviewExclusionRepository {

	private final JdbcTemplate jdbcTemplate;

	/**
	 * 담당 반 확인은 {@code manager_interview_list_view}를 그대로 쓴다.
	 *
	 * <p>{@code manager_assignment}를 여기서 다시 조인하지 않는 이유: 목록에 보이는 케이스와
	 * 제외할 수 있는 케이스가 같은 모집단이어야 한다. 조인을 두 벌로 두면 규칙이 갈라져
	 * "목록에는 있는데 제외하면 404"가 난다.
	 */
	@Override
	public Optional<CandidateState> findState(UUID managerUserId, UUID orgId, UUID candidateId) {
		List<CandidateState> rows = jdbcTemplate.query("""
				SELECT v.candidate_id,
				       v.candidate_status,
				       v.candidate_row_version,
				       v.interview_id,
				       v.interview_status
				FROM manager_interview_list_view v
				WHERE v.manager_user_id = ?
				  AND v.org_id          = ?
				  AND v.candidate_id    = ?
				""",
				(rs, rowNum) -> new CandidateState(
						rs.getObject("candidate_id", UUID.class),
						rs.getString("candidate_status"),
						rs.getInt("candidate_row_version"),
						rs.getObject("interview_id", UUID.class),
						rs.getString("interview_status")),
				managerUserId, orgId, candidateId);

		return rows.stream().findFirst();
	}

	/**
	 * 제외. {@code ck_interview_candidate_status_2}가 EXCLUDED에 사유·처리자·시각을 전부
	 * 요구하므로 네 값을 한 번에 채운다.
	 *
	 * <p>WHERE에 {@code row_version}을 넣어 낙관적 잠금을 건다 — 그 사이 다른 요청이
	 * 상태를 바꿨으면 0행이 갱신되고 호출부가 409를 낸다.
	 */
	@Override
	public int exclude(UUID candidateId, int expectedRowVersion, String reasonCode, UUID actorUserId) {
		return jdbcTemplate.update("""
				UPDATE interview_candidate
				   SET status                = 'EXCLUDED',
				       exclusion_reason_code = ?,
				       excluded_by           = ?,
				       excluded_at           = CURRENT_TIMESTAMP,
				       updated_at            = CURRENT_TIMESTAMP,
				       row_version           = row_version + 1
				 WHERE candidate_id = ?
				   AND row_version  = ?
				   AND status      <> 'EXCLUDED'
				""", reasonCode, actorUserId, candidateId, expectedRowVersion);
	}

	/**
	 * 재포함. 제외 4컬럼을 전부 NULL로 되돌린다 — 같은 CHECK가 ELIGIBLE·INTERVIEW_CREATED에서
	 * "제외 속성이 모두 NULL"을 요구한다.
	 *
	 * <p>복귀 상태({@code toStatus})는 호출부가 정한다. 연결된 면담이 남아 있으면
	 * {@code INTERVIEW_CREATED}, 없으면 {@code ELIGIBLE}이다(테이블 COMMENT).
	 */
	@Override
	public int reinclude(UUID candidateId, int expectedRowVersion, String toStatus) {
		return jdbcTemplate.update("""
				UPDATE interview_candidate
				   SET status                = ?,
				       exclusion_reason_code = NULL,
				       exclusion_note        = NULL,
				       excluded_by           = NULL,
				       excluded_at           = NULL,
				       updated_at            = CURRENT_TIMESTAMP,
				       row_version           = row_version + 1
				 WHERE candidate_id = ?
				   AND row_version  = ?
				   AND status       = 'EXCLUDED'
				""", toStatus, candidateId, expectedRowVersion);
	}

	/**
	 * 상태 이력. {@code before_snapshot}·{@code after_snapshot}은 상태 전이만 담는다 —
	 * 후보 행 전체를 복제하면 이력이 원장 크기만큼 불어나는데, 이 화면이 되돌리는 대상은
	 * 상태 하나뿐이라 그만큼이면 재현된다.
	 */
	@Override
	public void insertStatusHistory(UUID candidateId, String fromStatus, String toStatus,
			UUID actorUserId, String reasonCode, int candidateRowVersion, UUID requestId) {
		jdbcTemplate.update("""
				INSERT INTO interview_candidate_status_history
				    (candidate_id, from_status, to_status, changed_by, changed_at,
				     reason_code, before_snapshot, after_snapshot, candidate_row_version, request_id)
				VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP,
				        ?, ?::jsonb, ?::jsonb, ?, ?)
				""",
				candidateId, fromStatus, toStatus, actorUserId,
				reasonCode,
				"{\"status\":\"" + fromStatus + "\"}",
				"{\"status\":\"" + toStatus + "\"}",
				candidateRowVersion, requestId);
	}
}
