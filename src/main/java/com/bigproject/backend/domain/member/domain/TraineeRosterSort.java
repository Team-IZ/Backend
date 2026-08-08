package com.bigproject.backend.domain.member.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "TraineeRosterSort", description = "명단 정렬 기준. NAME(이름순) · RECENT_ENROLLED(최근 등록순)", enumAsRef = true)
public enum TraineeRosterSort {
	NAME,
	RECENT_ENROLLED
}
