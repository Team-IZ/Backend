package com.bigproject.backend.domain.reporting.domain;

import com.bigproject.backend.domain.disclosure.domain.DisclosureScope;
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
 * report 테이블 매핑 엔티티. 리포트 1건의 <b>신원과 공개 상태</b>만 들고 있다.
 *
 * <p>본문(개념·문답·서술)은 여기 없다 — {@code report_snapshot.summary_payload}에 있다
 * ({@link ReportSnapshot}). 발행 시점에 얼린 스냅샷이라 본문을 이 행에 두면
 * 재생성할 때마다 덮어써야 하고, 그러면 "발행 당시에 학생이 본 것"이 사라진다.
 *
 * <p>⚠ <b>감사 컬럼이 없다.</b> v07 DDL의 report 테이블에는 created_at·updated_at·row_version이
 * 없다. 다른 도메인 엔티티(예: {@code Organization})를 복사해 오면 validate에서 깨진다.
 *
 * <h2>DB CHECK 두 개를 반드시 지켜야 한다</h2>
 *
 * <p><b>① ck_report_assessment_round_id</b> — 대상 조합이 셋 중 하나여야 한다.
 * <pre>
 * 기수 단위 : type∈(COHORT_CURRICULUM_DIAGNOSIS, COHORT_OUTCOME) · class·user·round 전부 NULL
 * 개인 단위 : user≠NULL · round≠NULL · class=NULL
 * 반  단위 : class≠NULL · round≠NULL · user=NULL
 * </pre>
 * 생성은 {@link #forTrainee}·{@link #forCohortDiagnosis} 두 팩토리로만 하고 setter를 열지 않는다 —
 * 필드를 하나씩 채우게 두면 세 조합 밖의 상태가 만들어진다.
 *
 * <p><b>② ck_report_trainee_release_status_2</b> — 공개 상태와 나머지 3컬럼의 조합.
 * {@link TraineeReleaseStatus} javadoc 참고. 전이는 {@link #withhold}·{@link #release}로만 한다.
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
	 * 발행 시각. TR-04의 `발행 07-15` 표기이자 <b>다시 보기 창의 기산점</b>이다
	 * (MG-06 정의서 §3 "발행일이 다시 보기 창의 기산점 — 발행 +3일").
	 * NULL이면 아직 발행 전이라 TR-04에서 `PENDING_PUBLISH`로 그린다.
	 */
	@Column(name = "published_at")
	private Instant publishedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "trainee_release_status", nullable = false, length = 100)
	private TraineeReleaseStatus traineeReleaseStatus;

	/** Disclosure 도메인이 읽는 값. release 상태와 짝이 맞아야 한다(CHECK ②). */
	@Enumerated(EnumType.STRING)
	@Column(name = "trainee_disclosure_scope", length = 100)
	private DisclosureScope traineeDisclosureScope;

	@Column(name = "trainee_released_at")
	private Instant traineeReleasedAt;

	@Column(name = "trainee_released_by")
	private UUID traineeReleasedBy;

	private Report(UUID orgId, UUID cohortId, ReportType reportType) {
		this.orgId = orgId;
		this.cohortId = cohortId;
		this.reportType = reportType;
		this.lifecycleStatus = ReportLifecycleStatus.DRAFT;
		this.traineeReleaseStatus = TraineeReleaseStatus.NOT_CONFIGURED;
	}

	/**
	 * 개인 리포트 생성(TR-04). CHECK ①의 "개인 단위" 조합을 만든다 — classId는 채우지 않는다.
	 * 생성 직후는 DRAFT·NOT_CONFIGURED이며, 본문이 채워진 뒤 {@link #publish}로 발행한다.
	 */
	public static Report forTrainee(UUID orgId, UUID cohortId, UUID userId, UUID assessmentRoundId) {
		Report report = new Report(orgId, cohortId, ReportType.TRAINEE_FINAL);
		report.userId = userId;
		report.assessmentRoundId = assessmentRoundId;
		return report;
	}

	/**
	 * 기수 수업 진단 리포트 생성(OP-05). CHECK ①의 "기수 단위" 조합이라
	 * class·user·round를 전부 비운 채로 둔다.
	 */
	public static Report forCohortDiagnosis(UUID orgId, UUID cohortId) {
		return new Report(orgId, cohortId, ReportType.COHORT_CURRICULUM_DIAGNOSIS);
	}

	/**
	 * 발행 처리. 본문 스냅샷이 활성화된 뒤에 부른다.
	 *
	 * <p>공개 범위는 <b>건드리지 않는다.</b> 발행(published_at)과 교육생 공개
	 * (trainee_release_status)는 서로 다른 사건이다 — 발행됐지만 공개 범위를 아직 안 정한
	 * 상태가 TR-04의 `PENDING_VISIBILITY`다.
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
	 * 비공개로 확정. CHECK ②가 WITHHELD일 때 scope=PRIVATE·시각·주체 NULL을 요구하므로
	 * 세 값을 함께 맞춘다.
	 */
	public void withhold() {
		this.traineeReleaseStatus = TraineeReleaseStatus.WITHHELD;
		this.traineeDisclosureScope = DisclosureScope.PRIVATE;
		this.traineeReleasedAt = null;
		this.traineeReleasedBy = null;
	}

	/**
	 * 교육생에게 공개. CHECK ②가 RELEASED일 때 scope∈(SUMMARY,FULL)과 시각·주체를 모두
	 * 요구하므로 함께 채운다. PRIVATE으로 공개하려는 호출은 의미가 모순이라 막는다 —
	 * 비공개는 {@link #withhold}다.
	 */
	public void release(DisclosureScope scope, UUID releasedBy, Instant releasedAt) {
		if (scope == null || scope == DisclosureScope.PRIVATE) {
			throw new ReportException(ReportErrorCode.REPORT_DISCLOSURE_SCOPE_INVALID);
		}
		this.traineeReleaseStatus = TraineeReleaseStatus.RELEASED;
		this.traineeDisclosureScope = scope;
		this.traineeReleasedBy = releasedBy;
		this.traineeReleasedAt = releasedAt;
	}

	/** 교육생이 본문을 읽을 수 있는 상태인가. TR-04가 `PUBLISHED`로 그릴 조건이다. */
	public boolean isVisibleToTrainee() {
		return lifecycleStatus == ReportLifecycleStatus.ACTIVE
				&& publishedAt != null
				&& traineeReleaseStatus == TraineeReleaseStatus.RELEASED;
	}
}
