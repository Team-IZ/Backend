package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.reporting.presentation.dto.ManagedReportListResponse;

import java.util.UUID;

/**
 * 매니저가 담당하는 반의 리포트 목록 조회.
 *
 * <p>{@code TraineeReportService}(본인 것만)와 {@code CohortReportService}(기수 집계)에 이어
 * 세 번째 조회 경로다. 셋을 한 서비스로 합치지 않는 이유는 <b>권한 주체가 다르기 때문</b>이다 —
 * 교육생은 인증 주체로, 오퍼레이터는 기수로, 매니저는 배정으로 범위가 정해진다.
 */
public interface ManagedReportService {

	/**
	 * @param managerUserId 요청 매니저. 이 사람이 <b>지금</b> 담당하는 반만 대상이다.
	 * @param orgId         테넌트 경계. 배정 경로만으로도 사실상 같은 기관이지만 값으로도 대조한다.
	 * @param cohortId      선택 필터. null이면 담당 반 전체 기수를 본다.
	 * @param roundId       선택 필터. 회차별 공개 범위 일괄 지정 화면이 쓴다.
	 * @param classId       선택 필터. 담당 반이 여럿일 때 하나만 보기.
	 */
	ManagedReportListResponse findManagedReports(
			UUID managerUserId, UUID orgId, UUID cohortId, UUID roundId, UUID classId);
}
