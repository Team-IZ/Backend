package com.bigproject.backend.domain.academicoperations.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CohortStatus", description = "기수 진행 상태. PLANNED(개설 예정) · RUNNING(진행 중) · CLOSED(종료)", enumAsRef = true)
public enum CohortStatus {
	PLANNED,
	RUNNING,
	CLOSED
}