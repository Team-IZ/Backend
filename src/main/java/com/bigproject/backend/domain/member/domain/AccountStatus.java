package com.bigproject.backend.domain.member.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AccountStatus", description = "계정 상태. INVITED(초대됨) · ACTIVE(활성) · LOCKED(잠김) · INACTIVE(정지)", enumAsRef = true)
public enum AccountStatus {
	INVITED,
	ACTIVE,
	LOCKED,
	INACTIVE
}
