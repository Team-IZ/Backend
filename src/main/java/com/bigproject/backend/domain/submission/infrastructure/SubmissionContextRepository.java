package com.bigproject.backend.domain.submission.infrastructure;

import com.bigproject.backend.domain.submission.domain.Submission;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 제출 가능 여부 판정에 필요한 값을 한 번에 읽는다.
 *
 * <p>회차·팀·소속·기관 정책이 서로 다른 테이블에 흩어져 있어 엔티티를 도메인마다 새로 만들면
 * 읽기 전용 매핑이 5개 늘어난다. 여기서는 판정에 쓰는 컬럼만 네이티브 조회로 가져온다.
 *
 * <p>{@code trainee_home_round_view.can_submit}을 쓰지 않는 이유는 그 컬럼이 {@code round_status}를 보지 않아
 * {@code PLANNED} 회차도 제출 가능으로 반환하기 때문이다.
 */
public interface SubmissionContextRepository extends Repository<Submission, UUID> {

	/**
	 * 호출자가 해당 회차에서 제출할 때 필요한 컨텍스트.
	 *
	 * <p>결과가 비어 있으면 회차가 없거나, 삭제됐거나, 호출자가 그 회차 프로젝트의 유효 팀 구성원이 아니다.
	 * 세 경우를 구분하지 않는 이유는 남의 회차 존재 여부를 응답으로 알려주지 않기 위해서다.
	 */
	@Query(value = """
			SELECT r.org_id            AS orgId,
			       r.project_id        AS projectId,
			       t.team_id           AS teamId,
			       r.status            AS roundStatus,
			       r.submission_due_at AS submissionDueAt,
			       COALESCE(op.allow_github_integration, TRUE) AS allowGithubIntegration,
			       COALESCE(op.allow_zip_submission, TRUE)     AS allowZipSubmission
			  FROM project_assessment_round r
			  JOIN project_membership pm
			    ON pm.project_id = r.project_id
			   AND pm.user_id    = :userId
			   AND pm.status     = 'ACTIVE'
			  JOIN team_membership tm
			    ON tm.project_membership_id = pm.project_membership_id
			   AND (tm.to_at IS NULL OR tm.to_at > CURRENT_TIMESTAMP)
			  JOIN team t
			    ON t.team_id    = tm.team_id
			   AND t.project_id = r.project_id
			   AND t.deleted_at IS NULL
			  LEFT JOIN organization_policy op
			    ON op.org_id = r.org_id
			   AND op.status = 'ACTIVE'
			 WHERE r.assessment_round_id = :assessmentRoundId
			   AND r.deleted_at IS NULL
			""", nativeQuery = true)
	Optional<SubmissionContext> findSubmissionContext(
			@Param("userId") UUID userId,
			@Param("assessmentRoundId") UUID assessmentRoundId
	);

	interface SubmissionContext {
		UUID getOrgId();

		/** repository 행을 만들 때 필요하다. repository.project_id 가 NOT NULL 이다. */
		UUID getProjectId();

		UUID getTeamId();

		String getRoundStatus();

		Instant getSubmissionDueAt();

		boolean getAllowGithubIntegration();

		boolean getAllowZipSubmission();
	}
}
