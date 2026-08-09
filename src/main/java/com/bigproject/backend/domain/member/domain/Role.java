package com.bigproject.backend.domain.member.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Role", description = "계정 역할. SUPER_ADMIN · OPERATOR · MANAGER · TRAINEE", enumAsRef = true)
public enum Role {
	SUPER_ADMIN,
	OPERATOR,
	MANAGER,
	TRAINEE
}
