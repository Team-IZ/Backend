package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.reporting.presentation.dto.TraineeReportsResponse;

import java.util.UUID;

/** TR-04 `내 리포트` 조회와, 매니저가 담당 교육생의 같은 화면을 보는 경로. */
public interface TraineeReportService {

	/** 회차 목록 + 회차별 본문을 한 번에. 화면이 이 응답 하나로 그려진다. */
	TraineeReportsResponse findMyReports(UUID userId);

	/**
	 * 매니저가 담당 교육생 한 명의 리포트를 <b>교육생과 같은 모양으로</b> 본다(MG 교육생 상세).
	 *
	 * <p>응답 타입이 {@link #findMyReports}와 같은 것은 의도한 것이다. 매니저 화면의 리포트 라인은
	 * "이 회차 리포트가 만들어졌나 · 발행됐나"를 회차별로 보여주고 펼치면 본문을 그대로 그리는데,
	 * 그 본문이 학생이 보는 것과 달라야 할 이유가 없다. 모양을 따로 두면 같은 리포트를 설명하는
	 * 말이 두 벌이 된다.
	 *
	 * <p>다른 점은 <b>다시 보기 잠금이 걸리지 않는다</b>는 것 하나다. 잠금은 학생이 답을 먼저 보고
	 * 다시 푸는 것을 막기 위한 것이라 매니저에게는 해당이 없다.
	 *
	 * <p>🔴 <b>담당 여부는 호출부가 확인한다</b>({@code ReportController}). 담당하지 않는 교육생이면
	 * 404다 — 403으로 구분해 주면 "그 교육생이 존재한다"는 사실이 새어 나간다.
	 */
	TraineeReportsResponse findTraineeReportsForManager(UUID traineeUserId);

	/**
	 * 리포트 1건. {@link #findMyReports}가 이미 전량을 주므로 화면은 대개 쓰지 않지만,
	 * 리포트를 reportId 하나로 가리킬 수 있어야 하는 자리가 있어 남긴다.
	 */
	TraineeReportsResponse.RoundReportResponse findMyReport(UUID userId, UUID reportId);
}
