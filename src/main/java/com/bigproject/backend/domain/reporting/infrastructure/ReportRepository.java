package com.bigproject.backend.domain.reporting.infrastructure;

import com.bigproject.backend.domain.reporting.domain.Report;
import com.bigproject.backend.domain.reporting.domain.ReportLifecycleStatus;
import com.bigproject.backend.domain.reporting.domain.ReportType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {

	/**
	 * TR-04 좌측 회차 목록 + 본문 전량. 교육생 1명의 개인 리포트를 회차 순으로 가져온다.
	 *
	 * <p>정렬을 Pageable로 받지 않고 JPQL에 고정한다 — TR-04는 페이지네이션이 없고
	 * "최신 회차가 기본 선택"(정의서 §3)이라 정렬이 화면 규칙이지 사용자 선택이 아니다.
	 *
	 * <p>{@code publishedAt DESC NULLS FIRST}인 이유: 아직 발행되지 않은 회차
	 * (publishedAt=NULL, 화면상 `PENDING_PUBLISH`)가 가장 최근 회차다. 뒤로 보내면
	 * "지금 진행 중인 회차"가 목록 맨 아래에 붙어 기본 선택이 지난 회차가 된다.
	 *
	 * <p>SUPERSEDED는 재생성으로 대체된 이력이라 제외한다. DRAFT는 남긴다 —
	 * 화면이 `PENDING_PUBLISH`로 그려야 할 대상이기 때문이다.
	 */
	@Query("""
			SELECT r FROM Report r
			WHERE r.userId = :userId
			  AND r.reportType = :reportType
			  AND r.lifecycleStatus <> :excludedStatus
			ORDER BY r.publishedAt DESC NULLS FIRST
			""")
	List<Report> findTraineeReports(
			@Param("userId") UUID userId,
			@Param("reportType") ReportType reportType,
			@Param("excludedStatus") ReportLifecycleStatus excludedStatus
	);

	/**
	 * 기수 단위 리포트. OP-05가 쓴다.
	 *
	 * <p>기수 × 타입 조합으로 ACTIVE는 하나만 있어야 하지만 DB에 유니크 제약이 없어
	 * {@code Optional}이 아니라 목록으로 받는다 — 둘 이상이면 서비스 계층에서 최신 발행분을
	 * 고르고 경고 로그를 남긴다. 조용히 아무거나 고르면 화면이 지난 스냅샷을 보여준다.
	 */
	List<Report> findByCohortIdAndReportTypeAndLifecycleStatus(
			UUID cohortId,
			ReportType reportType,
			ReportLifecycleStatus lifecycleStatus
	);

	/** 단건 조회 시 소유자 확인까지 한 번에. 남의 리포트면 빈 값이라 403 분기가 단순해진다. */
	Optional<Report> findByReportIdAndUserId(UUID reportId, UUID userId);
}
