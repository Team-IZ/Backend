package com.bigproject.backend.domain.member.application;

import java.util.UUID;

public record TraineeCsvRow(
		int row,
		String name,
		String email,
		UUID classroomId
) {
	public TraineeCsvRow(int row, String name, String email) {
		this(row, name, email, null);
	}
}
