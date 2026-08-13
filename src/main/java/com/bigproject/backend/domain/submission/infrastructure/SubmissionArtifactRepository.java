package com.bigproject.backend.domain.submission.infrastructure;

import com.bigproject.backend.domain.submission.domain.SubmissionArtifact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SubmissionArtifactRepository extends JpaRepository<SubmissionArtifact, UUID> {

	/** uq_submission_artifact_submission_id 가 제출당 1건을 보장한다. */
	Optional<SubmissionArtifact> findBySubmissionId(UUID submissionId);
}
