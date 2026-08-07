package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.reporting.presentation.dto.TraineeReportsResponse;

import java.util.UUID;

/** TR-04 `내 리포트` 조회. 교육생 본인 것만 본다. */
public interface TraineeReportService {

	/** 회차 목록 + 회차별 본문을 한 번에. 화면이 이 응답 하나로 그려진다. */
	TraineeReportsResponse findMyReports(UUID userId);

	/**
	 * 리포트 1건. {@link #findMyReports}가 이미 전량을 주므로 화면은 쓰지 않지만,
	 * Disclosure({@code GET /reports/{reportId}/disclosure})가 reportId를 주소로 쓰기 때문에
	 * 리포트는 단건으로도 가리킬 수 있어야 한다.
	 */
	TraineeReportsResponse.RoundReportResponse findMyReport(UUID userId, UUID reportId);
}
