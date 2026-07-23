package com.bigproject.backend.domain.member.application;

public class InvitationConflictException extends RuntimeException {
	public InvitationConflictException(String message) {
		super(message);
	}
}
