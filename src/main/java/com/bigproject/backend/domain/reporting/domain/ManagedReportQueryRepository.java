package com.bigproject.backend.domain.reporting.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 매니저가 <b>담당하는 반</b>의 리포트 목록 조회 포트.
 *
 * <h2>왜 이 포트가 필요했나</h2>
 *
 * <p>매니저에게 열린 리포트 API는 {@code PUT /reports/{reportId}/disclosure} 하나뿐이었다.
 * 공개 범위를 정할 수는 있는데 <b>정할 대상을 찾을 방법이 없었다</b> — 상태를 확인하려면
 * 상태를 바꿔야 하는 모순이다. 그 공백을 메운다.
 *
 * <h2>권한 범위는 담당 반 전체다</h2>
 *
 * <p>면담 대상 교육생으로 좁히지 않는다. 공개 범위 지정은 면담 여부와 무관하게 회차마다
 * 발생하는 일이라, 면담 대상만 보이면 나머지 교육생의 리포트가 영원히 미지정으로 남는다.
 *
 * <p>조인 경로는 {@code ReportDisclosureRepository.isManagedBy}와 <b>같아야 한다</b> —
 * 목록에 보이는데 수정은 404가 나거나 그 반대가 되면 안 된다. 그래서 이력 테이블 두 곳의
 * 유효 조건({@code cohort_member.left_at IS NULL} · {@code class_membership.unassigned_at IS NULL} ·
 * {@code manager_assignment.unassigned_at IS NULL})과 {@code org_id} 대조를 그대로 옮겼다.
 */
public interface ManagedReportQueryRepository {

	/**
	 * 담당 반 교육생들의 개인 리포트 목록.
	 *
	 * <p>필터는 전부 선택이다. null이면 그 축으로 좁히지 않는다.
	 * 담당하지 않는 리포트는 <b>목록에서 빠질 뿐</b> 404가 아니다 — 목록 조회에서 404는
	 * "그런 반이 없다"는 뜻이 되어 버려 실제로 담당이 없는 경우와 구분되지 않는다.
	 */
	List<ManagedReportRow> findManagedReports(
			UUID managerUserId,
			UUID orgId,
			UUID cohortId,
			UUID assessmentRoundId,
			UUID classId
	);

	/**
	 * 목록 한 줄. 매니저 화면이 "누구의 어느 회차 리포트가 발행됐고 지금 어떤 공개 상태인가"를
	 * 이 한 줄로 판단한다.
	 *
	 * @param publishedAt           발행 시각. null이면 아직 발행 전이다 — 발행과 공개는 다른 사건이라
	 *                              이 값이 null이어도 공개 범위는 미리 정해 둘 수 있다.
	 * @param traineeReleaseStatus  {@code NOT_CONFIGURED} · {@code WITHHELD} · {@code RELEASED}.
	 *                              매니저 화면의 `공개 범위 미지정` 배지가 첫 값이다.
	 * @param traineeDisclosureScope {@code PRIVATE} · {@code SUMMARY} · {@code FULL}. 미지정이면 null.
	 * @param lifecycleStatus       {@code DRAFT} · {@code ACTIVE} · {@code SUPERSEDED}.
	 *                              {@code bodyVisible} 계산에 쓴다.
	 */
	record ManagedReportRow(
			UUID reportId,
			UUID assessmentRoundId,
			String roundName,
			int roundNo,
			UUID traineeUserId,
			String traineeName,
			UUID classId,
			String className,
			UUID cohortId,
			String lifecycleStatus,
			Instant publishedAt,
			String traineeReleaseStatus,
			String traineeDisclosureScope,
			Instant traineeReleasedAt
	) {
	}
}
