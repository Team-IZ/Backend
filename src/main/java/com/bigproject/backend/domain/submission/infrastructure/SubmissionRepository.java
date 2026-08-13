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

	/** 멱등 재요청 판정용. uq_submission_request_idempotency_key 가 키당 1건을 보장한다. */
	Optional<Submission> findByRequestIdempotencyKey(UUID requestIdempotencyKey);
}
