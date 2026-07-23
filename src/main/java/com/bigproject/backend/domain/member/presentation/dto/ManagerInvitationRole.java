package com.bigproject.backend.domain.member.presentation.dto;

import com.bigproject.backend.domain.member.domain.Role;

public enum ManagerInvitationRole {
	LEAD_MANAGER,
	MANAGER;

	public Role toRole() {
		return Role.valueOf(name());
	}
}
