package com.bigproject.backend.domain.manager.domain;

// MG-05 교육생 명부 정렬 방식. manager/trainees/list=DEFAULT, /top=EXCELLENT_OCCURRENCE_COUNT_DESC, /risk=LOW_STAGE_CONCEPT_COUNT_DESC 매핑
public enum TraineeRosterSortMode {
	DEFAULT,
	EXCELLENT_OCCURRENCE_COUNT_DESC,
	LOW_STAGE_CONCEPT_COUNT_DESC
}
