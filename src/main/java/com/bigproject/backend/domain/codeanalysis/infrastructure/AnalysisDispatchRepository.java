package com.bigproject.backend.domain.codeanalysis.infrastructure;

import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJob;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 마감된 회차에서 분석 대상 제출을 찾는다.
 *
 * <p>엔티티를 새로 만들지 않고 네이티브 조회로 두는 이유는, 여기 필요한 것이 회차·팀·제출을 가로지르는
 * 읽기 한 번뿐이기 때문이다. 회차 엔티티를 만들면 이 배치 하나 때문에 매핑이 늘어난다.
 */
public interface AnalysisDispatchRepository extends Repository<AnalysisJob, UUID> {

	/**
	 * 마감이 지났는데 아직 분석 실행이 없는 현재 제출들.
	 *
	 * <p>이미 실행이 있는 제출을 제외하는 것은 성능이 아니라 <b>정확성</b> 때문이다. 배치가 겹쳐 돌면
	 * 같은 제출에 두 번째 job을 만들려 하고, {@code uq_analysis_job_active}가 그걸 예외로 터뜨린다.
	 * 예외로 막는 것과 애초에 고르지 않는 것은 로그의 소음 차이가 크다.
	 *
	 * <p>{@code status='ACCEPTED'}만 고른다. ZIP은 내용 검증이 아직 없어 {@code VALIDATING}에
	 * 머무르는데, 그 상태로 분석에 보내면 검증되지 않은 파일을 분석하게 된다.
	 */
	@Query(value = """
			SELECT s.submission_id      AS submissionId,
			       s.org_id             AS orgId,
			       s.team_id            AS teamId,
			       s.assessment_round_id AS assessmentRoundId,
			       s.method             AS method,
			       s.requested_branch   AS requestedBranch,
			       r.repo_url           AS repositoryUrl
			  FROM submission s
			  JOIN project_assessment_round pr
			    ON pr.assessment_round_id = s.assessment_round_id
			   AND pr.deleted_at IS NULL
			  LEFT JOIN repository r ON r.repository_id = s.repository_id
			 WHERE s.is_current = TRUE
			   AND s.status     = 'ACCEPTED'
			   AND pr.submission_due_at <= :now
			   AND NOT EXISTS (
			       SELECT 1 FROM analysis_job j WHERE j.submission_id = s.submission_id
			   )
			 ORDER BY pr.submission_due_at, s.team_id
			""", nativeQuery = true)
	List<DispatchTarget> findDueSubmissions(@Param("now") Instant now);

	interface DispatchTarget {
		UUID getSubmissionId();

		UUID getOrgId();

		UUID getTeamId();

		UUID getAssessmentRoundId();

		String getMethod();

		String getRequestedBranch();

		/** ZIP 제출은 repository 행이 없어 NULL이다. */
		String getRepositoryUrl();
	}
}
