package com.bigproject.backend.domain.disclosure.infrastructure;

import com.bigproject.backend.domain.reporting.domain.Report;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Disclosure 도메인이 리포트의 <b>공개 상태 컬럼</b>에 접근하기 위한 좁은 리포지토리.
 *
 * <p><b>왜 Reporting의 {@code Report} 엔티티를 쓰는가.</b> 공개 상태 4컬럼
 * ({@code trainee_release_status} · {@code trainee_disclosure_scope} ·
 * {@code trainee_released_at} · {@code trainee_released_by})이 {@code report} 행에 함께 있고,
 * DB CHECK {@code ck_report_trainee_release_status_2}가 이 넷을 한 덩어리로 강제한다.
 * 별도 테이블로 뗄 수 없으므로 <b>엔티티는 공유하고 책임만 나눈다</b> —
 * 전이 규칙은 {@code Report#release}·{@code Report#withhold}가 이미 들고 있고,
 * 이 도메인은 그것을 <b>누가 언제 부를 수 있는지</b>를 정한다.
 *
 * <p>{@code JpaRepository}가 아니라 {@link Repository}를 상속해 <b>필요한 4개만</b> 연다.
 * 전체 CRUD를 열면 Disclosure가 리포트를 지우거나 발행할 수 있게 되는데,
 * 그건 Reporting의 일이다.
 */
public interface ReportDisclosureRepository extends Repository<Report, UUID> {

	/** 매니저 경로. 소유자 확인은 {@link #isManagedBy}가 따로 한다. */
	Optional<Report> findByReportId(UUID reportId);

	/**
	 * 교육생 경로. 조회와 소유자 확인을 한 번에 한다 —
	 * 남의 리포트면 빈 값이라 호출부가 403/404를 고민할 필요가 없다.
	 */
	Optional<Report> findByReportIdAndUserId(UUID reportId, UUID userId);

	<S extends Report> S save(S report);

	/**
	 * 이 매니저가 리포트 대상 교육생을 <b>지금</b> 담당하고 있는가.
	 *
	 * <p>경로는 {@code report → cohort_member → class_membership → manager_assignment} 넷이고,
	 * 중간 두 곳이 이력 테이블이라 <b>해제되지 않은 행만</b> 센다
	 * ({@code left_at IS NULL} · {@code unassigned_at IS NULL}). 이 조건을 빼면
	 * 지난 기수에 잠깐 담당했던 매니저가 계속 공개 범위를 바꿀 수 있다.
	 *
	 * <p>기수 단위 리포트({@code COHORT_*})는 {@code report.user_id}가 NULL이라 첫 조인에서
	 * 걸러진다 — 별도 타입 검사가 필요 없다.
	 *
	 * <p>{@code org_id}를 세 곳에서 함께 맞춘다. 배정 경로만으로도 사실상 같은 기관이지만,
	 * 테넌트 경계는 <b>경유하는 경로가 아니라 값으로</b> 확인해야 한다(RLS 원칙과 같다).
	 */
	@Query(value = """
			SELECT EXISTS (
			    SELECT 1
			    FROM report r
			    JOIN cohort_member cm
			      ON cm.cohort_id = r.cohort_id
			     AND cm.user_id   = r.user_id
			     AND cm.org_id    = r.org_id
			     AND cm.left_at IS NULL
			    JOIN class_membership clm
			      ON clm.cohort_member_id = cm.cohort_member_id
			     AND clm.org_id           = r.org_id
			     AND clm.unassigned_at IS NULL
			    JOIN manager_assignment ma
			      ON ma.class_id        = clm.class_id
			     AND ma.org_id          = r.org_id
			     AND ma.manager_user_id = :managerUserId
			     AND ma.unassigned_at IS NULL
			    WHERE r.report_id = :reportId
			      AND r.org_id    = :orgId
			)
			""", nativeQuery = true)
	boolean isManagedBy(
			@Param("reportId") UUID reportId,
			@Param("managerUserId") UUID managerUserId,
			@Param("orgId") UUID orgId
	);
}
