package com.bigproject.backend.domain.member.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ManagerRosterSort", description = "매니저 목록 정렬 기준. NAME(이름순) · ASSIGNED_TRAINEE_COUNT(담당 인원순)",
		enumAsRef = true)
public enum ManagerRosterSort {
	NAME,
	ASSIGNED_TRAINEE_COUNT
}
