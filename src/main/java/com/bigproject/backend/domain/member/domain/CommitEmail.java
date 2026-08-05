package com.bigproject.backend.domain.member.domain;

import java.time.Instant;

public record CommitEmail(
		String commitEmail,
		String status,
		String verificationMethod,
		Instant verifiedAt,
		Instant updatedAt
) {
	/** ck_app_user_commit_email_updated_at이 all-or-nothing이라 부분 등록 상태는 존재하지 않는다. */
	public static CommitEmail unregistered() {
		return new CommitEmail(null, null, null, null, null);
	}

	public boolean registered() {
		return commitEmail != null;
	}
}
