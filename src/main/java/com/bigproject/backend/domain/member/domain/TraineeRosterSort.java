package com.bigproject.backend.domain.member.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "TraineeRosterSort", description = "명단 정렬 기준. NAME · RECENT_ENROLLED · RISK · EXCELLENCE", enumAsRef = true)
public enum TraineeRosterSort {
	NAME,
	RECENT_ENROLLED,
	RISK,
	EXCELLENCE
}
