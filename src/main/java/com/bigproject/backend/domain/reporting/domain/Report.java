package com.bigproject.backend.domain.reporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * report 테이블 매핑 엔티티. 리포트 1건의 <b>신원과 발행 여부</b>만 들고 있다.
 *
 * <p>본문(개념·문답·서술)은 여기 없다 — {@code report_snapshot.summary_payload}에 있다
 * ({@link ReportSnapshot}). 발행 시점에 얼린 스냅샷이라 본문을 이 행에 두면
 * 재생성할 때마다 덮어써야 하고, 그러면 "발행 당시에 학생이 본 것"이 사라진다.
 *
 * <p>⚠ <b>감사 컬럼이 없다.</b> v07 DDL의 report 테이블에는 created_at·updated_at·row_version이
 * 없다. 다른 도메인 엔티티(예: {@code Organization})를 복사해 오면 validate에서 깨진다.
 *
 * <h2>DB CHECK ck_report_assessment_round_id를 반드시 지켜야 한다</h2>
 *
 * <p>대상 조합이 셋 중 하나여야 한다.
 * <pre>
 * 기수 단위 : type∈(COHORT_CURRICULUM_DIAGNOSIS, COHORT_OUTCOME) · class·user·round 전부 NULL
 * 개인 단위 : user≠NULL · round≠NULL · class=NULL
 * 반  단위 : class≠NULL · round≠NULL · user=NULL
 * </pre>
 * 생성은 {@link #forTrainee}·{@link #forCohortDiagnosis} 두 팩토리로만 하고 setter를 열지 않는다 —
 * 필드를 하나씩 채우게 두면 세 조합 밖의 상태가 만들어진다.
 *
 * <h2>공개 상태 4컬럼을 매핑하지 않는다</h2>
 *
 * <p>{@code trainee_release_status}·{@code trainee_disclosure_scope}·{@code trainee_released_at}
 * ·{@code trainee_released_by}는 테이블에 아직 남아 있지만 여기서 다루지 않는다.
 * <b>공개/비공개 개념이 없어졌기 때문이다</b>(2026-08-19) — 발행되면 그 순간 학생이 본다.
 *
 * <p>매핑하지 않아도 되는 것은 마이그레이션
 * {@code docs/migration/2026-08-19_report_disclosure_removal.sql}이 두 컬럼에 DEFAULT를 주고
 * 조합 제약을 없앴기 때문이다. 🔴 <b>그 DDL이 배포보다 먼저다</b> — 적용하지 않은 DB에서는
 * INSERT가 NOT NULL 위반으로 실패한다. 컬럼 자체를 지우는 것은 뷰 6개가 함께 움직여야 해서
 * 2단계로 뺐다(마이그레이션 파일 6절).
 */
@Getter
@Entity
@Table(name = "report")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report {

	@Id
	@UuidGenerator
	@Column(name = "report_id", updatable = false, nullable = false)
	private UUID reportId;

	@Column(name = "org_id", nullable = false)
	private UUID orgId;

	@Column(name = "cohort_id", nullable = false)
	private UUID cohortId;

	/** 반 단위 리포트일 때만. 개인·기수 단위에서는 NULL이어야 한다(CHECK ①). */
	@Column(name = "class_id")
	private UUID classId;

	/** 개인 단위 리포트의 대상 교육생. TR-04가 이 값으로 조회한다. */
	@Column(name = "user_id")
	private UUID userId;

	/** 어느 회차의 리포트인가. 개인·반 단위에서는 필수, 기수 단위에서는 NULL이다(CHECK ①). */
	@Column(name = "assessment_round_id")
	private UUID assessmentRoundId;

	@Enumerated(EnumType.STRING)
	@Column(name = "report_type", nullable = false, length = 100)
	private ReportType reportType;

	@Enumerated(EnumType.STRING)
	@Column(name = "lifecycle_status", nullable = false, length = 30)
	private ReportLifecycleStatus lifecycleStatus;

	/** 예약 발행 시각. 지금은 즉시 발행만 쓰므로 대개 NULL이다. */
	@Column(name = "scheduled_publish_at")
	private Instant scheduledPublishAt;

	/**
	 * 발행 시각이자 <b>학생이 볼 수 있게 된 시각</b>이다. TR-04의 `발행 07-15` 표기이고
	 * <b>다시 보기 창의 기산점</b>이기도 하다(MG-06 정의서 §3 "발행 +3일").
	 * NULL이면 아직 발행 전이라 TR-04에서 `PENDING_PUBLISH`로 그린다.
	 *
	 * <p>🔴 <b>이 한 컬럼이 열람 가능 여부의 유일한 근거다.</b> 종전에는 발행과 공개가 다른
	 * 사건이라 {@code trainee_release_status}가 따로 답했는데, 그 개념이 없어지면서 이 값이
	 * 둘을 겸한다. 뷰의 판정식도 전부 이 컬럼으로 바뀌었다(마이그레이션 4절).
	 */
	@Column(name = "published_at")
	private Instant publishedAt;

	private Report(UUID orgId, UUID cohortId, ReportType reportType) {
		this.orgId = orgId;
		this.cohortId = cohortId;
		this.reportType = reportType;
		this.lifecycleStatus = ReportLifecycleStatus.DRAFT;
	}

	/**
	 * 개인 리포트 생성(TR-04). CHECK의 "개인 단위" 조합을 만든다 — classId는 채우지 않는다.
	 * 생성 직후는 DRAFT이며, 본문이 채워진 뒤 {@link #publish}로 발행한다.
	 */
	public static Report forTrainee(UUID orgId, UUID cohortId, UUID userId, UUID assessmentRoundId) {
		Report report = new Report(orgId, cohortId, ReportType.TRAINEE_FINAL);
		report.userId = userId;
		report.assessmentRoundId = assessmentRoundId;
		return report;
	}

	/**
	 * 기수 수업 진단 리포트 생성(OP-05). CHECK의 "기수 단위" 조합이라
	 * class·user·round를 전부 비운 채로 둔다.
	 */
	public static Report forCohortDiagnosis(UUID orgId, UUID cohortId) {
		return new Report(orgId, cohortId, ReportType.COHORT_CURRICULUM_DIAGNOSIS);
	}

	/**
	 * 발행 처리. 본문 스냅샷이 활성화된 뒤에 부른다.
	 *
	 * <p><b>발행이 곧 공개다.</b> 이 메서드가 돌면 학생은 그 즉시 자기 리포트를 본다 —
	 * 종전처럼 매니저가 공개 범위를 정해 주기를 기다리지 않는다. 그래서 여기서 따로
	 * 할 일도 없다. 공개 상태 컬럼은 DB DEFAULT가 채운다.
	 */
	public void publish(Instant publishedAt) {
		this.lifecycleStatus = ReportLifecycleStatus.ACTIVE;
		this.publishedAt = publishedAt;
	}

	/** 재생성으로 대체됐음을 기록한다. 조회 대상에서 빠진다. */
	public void supersede() {
		this.lifecycleStatus = ReportLifecycleStatus.SUPERSEDED;
	}

	/**
	 * 교육생이 본문을 읽을 수 있는 상태인가. TR-04가 `PUBLISHED`로 그릴 조건이다.
	 *
	 * <p>{@code lifecycle_status}를 함께 보는 이유는 재생성이다 — 대체된 리포트
	 * ({@code SUPERSEDED})는 발행 시각이 남아 있어도 학생이 볼 것이 아니다.
	 */
	public boolean isVisibleToTrainee() {
		return lifecycleStatus == ReportLifecycleStatus.ACTIVE
				&& publishedAt != null;
	}
}
