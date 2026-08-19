package com.bigproject.backend.domain.reporting.domain;

import java.util.UUID;

/**
 * 매니저가 이 교육생을 <b>지금</b> 담당하고 있는가.
 *
 * <p>매니저가 담당 교육생의 리포트를 교육생과 같은 모양으로 보는 경로
 * ({@code GET /reports/managed})의 유일한 관문이다. 종전에는 같은 판정이
 * {@code ReportDisclosureRepository.isManagedBy}에 있었는데, 공개/비공개 폐지로 그 도메인이
 * 통째로 없어지면서 <b>판정만 여기로 옮겨 왔다</b> — 없어진 것은 공개 권한이지 담당 개념이 아니다.
 */
public interface ManagerTraineeAccessRepository {

	/**
	 * 담당 여부.
	 *
	 * <p>경로는 {@code cohort_member → class_membership → manager_assignment} 셋이고,
	 * 앞의 둘이 이력 테이블이라 <b>해제되지 않은 행만</b> 센다
	 * ({@code left_at IS NULL} · {@code unassigned_at IS NULL}). 이 조건을 빼면
	 * 지난 기수에 잠깐 담당했던 매니저가 계속 남의 교육생 리포트를 볼 수 있다.
	 *
	 * <p>{@code org_id}를 세 곳에서 함께 맞춘다. 배정 경로만으로도 사실상 같은 기관이지만,
	 * 테넌트 경계는 <b>경유하는 경로가 아니라 값으로</b> 확인해야 한다(RLS 원칙과 같다).
	 */
	boolean isManagedBy(UUID traineeUserId, UUID managerUserId, UUID orgId);
}
