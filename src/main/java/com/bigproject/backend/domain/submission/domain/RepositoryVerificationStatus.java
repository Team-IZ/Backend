package com.bigproject.backend.domain.submission.domain;

/**
 * DB CHECK: ck_repository_verification_status — status IN ('PENDING','CHECKING','VERIFIED','FAILED')
 *
 * <p>"아직 확인하지 않음"에 해당하는 값은 {@code PENDING}이다. {@code UNVERIFIED}는 허용값이 아니라
 * INSERT 시 CHECK 위반으로 실패한다.
 */
public enum RepositoryVerificationStatus {
	PENDING,
	CHECKING,
	VERIFIED,
	FAILED
}
