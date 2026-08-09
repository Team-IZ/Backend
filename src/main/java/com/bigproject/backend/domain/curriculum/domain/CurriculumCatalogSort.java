package com.bigproject.backend.domain.curriculum.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** 교안 목록 정렬 기준(9차 R8). */
@Schema(
		name = "CurriculumCatalogSort",
		description = """
				교안 목록 정렬 기준.
				`RECENT`(최근 업로드 순, 기본) · `NAME`(파일명 오름차순) · `USAGE`(사용 회차 많은 순)""",
		enumAsRef = true
)
public enum CurriculumCatalogSort {

	/** 최근 업로드 순. 방금 올린 교안이 맨 위에 있어야 업로드 직후 화면이 자연스럽다. */
	RECENT,

	/** 파일명 오름차순. */
	NAME,

	/** 사용 회차가 많은 순. 삭제·교체 대상을 고를 때 쓴다. */
	USAGE
}
