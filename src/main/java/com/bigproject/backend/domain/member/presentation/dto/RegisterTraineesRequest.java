package com.bigproject.backend.domain.member.presentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record RegisterTraineesRequest(
		@NotEmpty List<@Valid Trainee> trainees
) {
	public record Trainee(
			@NotBlank String name,
			@NotBlank @Email String email,
			Long classroomId
	) {
	}
}
