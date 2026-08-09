package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.reporting.presentation.dto.CohortDiagnosisResponse;

import java.util.UUID;

/** OP-05 리포트 조회. 기수 하나의 수업 진단 문서 전체를 만든다. */
public interface CohortReportService {

	/**
	 * 수업 진단 리포트. 수업 진단 스냅샷과 기수 결산 스냅샷을 합쳐 문서 하나로 낸다.
	 * 결산 스냅샷이 아직 없으면 {@code IN_PROGRESS}로 내려간다.
	 */
	CohortDiagnosisResponse findClassDiagnosis(UUID cohortId);
}
