package com.bigproject.backend.domain.submission.infrastructure;

import com.bigproject.backend.domain.submission.domain.Submission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SubmissionRepository extends JpaRepository<Submission, UUID> {

	/**
	 * 팀·회차의 현재 제출. {@code uq_submission_current(team_id, assessment_round_id) WHERE is_current=TRUE}가
	 * 최대 1건을 보장한다.
	 */
	Optional<Submission> findByTeamIdAndAssessmentRoundIdAndCurrentIsTrue(UUID teamId, UUID assessmentRoundId);

	/**
	 * 멱등 재요청 판정용. 확인 실행 1건에 붙는 제출은 1건이므로, 같은 {@code X-Request-Id}로 만들어진
	 * verification을 찾으면 그때 접수한 제출에 그대로 도달한다.
	 */
	Optional<Submission> findByRepositoryVerificationId(UUID repositoryVerificationId);
}
