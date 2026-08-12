package com.bigproject.backend.domain.submission.domain;

/** DB CHECK: ck_submission_artifact_validation_status — validation_status IN ('VALIDATING','VERIFIED','FAILED') */
public enum ArtifactValidationStatus {
	VALIDATING,
	VERIFIED,
	FAILED
}
