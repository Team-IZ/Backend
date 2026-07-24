package com.bigproject.backend.domain.member.domain;

public enum TraineeInvitationFailureStatus {
	INVALID_EMAIL_FORMAT(1),
	DUPLICATE_EMAIL_IN_REQUEST(2),
	EXISTING_ORGANIZATION_TRAINEE_EMAIL(3);

	private final int code;

	TraineeInvitationFailureStatus(int code) {
		this.code = code;
	}

	public int code() {
		return code;
	}
}
