package com.bigproject.backend.domain.member.presentation.dto;

import com.bigproject.backend.domain.member.domain.Role;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record InviteManagerRequest(
		@NotBlank @Email String email,
		@NotNull Role role,
		List<@Valid ManagerAssignmentRequest> assignments
) {
	public InviteManagerRequest {
		assignments = assignments == null ? List.of() : List.copyOf(assignments);
	}

	@AssertTrue(message = "초대 가능한 권한은 총괄 또는 담당 매니저입니다.")
	public boolean isManagerRole() {
		return role == null || role == Role.LEAD_MANAGER || role == Role.MANAGER;
	}

	@AssertTrue(message = "총괄 매니저는 기관 전체를 담당하므로 기수·반을 배정하지 않습니다.")
	public boolean isAssignmentCompatibleWithRole() {
		return role == null || role == Role.MANAGER || assignments.isEmpty();
	}
}
