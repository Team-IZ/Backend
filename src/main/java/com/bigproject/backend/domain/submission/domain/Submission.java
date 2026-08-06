package com.bigproject.backend.domain.submission.domain;

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
 * submission 테이블 매핑 엔티티. 프로젝트 회차의 <b>팀 단위</b> 코드 제출 이력이다.
 *
 * <p>재제출은 기존 행 UPDATE가 아니라 새 행 INSERT이며 {@code supersedesSubmissionId}로 직전 제출을 가리킨다.
 * {@code uq_submission_current(team_id, assessment_round_id) WHERE is_current=TRUE}가 걸려 있어
 * 이전 행을 내리는 것과 새 행을 넣는 것이 같은 트랜잭션 안에 있어야 한다.
 *
 * <p>분석 입력 5종({@code analysisInputHash}·{@code gitHistory}·{@code codeSnippets}·
 * {@code analysisInputFileCount}·{@code analysisInputCapturedAt})은
 * {@code ck_submission_analysis_input_captured_at}이 all-or-nothing으로 묶으므로 제출 시점에는 전부 NULL이고
 * 마감 후 분석 배치가 한 번에 채운다. 이 클래스는 그 컬럼들을 매핑만 하고 채우지 않는다.
 */
@Getter
@Entity
@Table(name = "submission")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Submission {

	@Id
	@UuidGenerator
	@Column(name = "submission_id", updatable = false, nullable = false)
	private UUID submissionId;

	@Column(name = "org_id", nullable = false, updatable = false)
	private UUID orgId;

	@Column(name = "team_id", nullable = false, updatable = false)
	private UUID teamId;

	@Column(name = "assessment_round_id", nullable = false, updatable = false)
	private UUID assessmentRoundId;

	@Enumerated(EnumType.STRING)
	@Column(name = "method", nullable = false, updatable = false, length = 100)
	private SubmissionMethod method;

	/**
	 * 분석 성공 후에만 채워진다. GitHub 접근 주체가 AI 서버라서 제출 시점에는 알 수 없다(S-01).
	 * 제출된 URL 원문은 {@link #repositoryVerificationId}가 가리키는 행에만 남는다.
	 */
	@Column(name = "repository_id")
	private UUID repositoryId;

	@Column(name = "requested_branch", columnDefinition = "text")
	private String requestedBranch;

	@Column(name = "resolved_branch", columnDefinition = "text")
	private String resolvedBranch;

	@Column(name = "source_commit_sha", length = 64)
	private String sourceCommitSha;

	@Column(name = "source_commit_message", columnDefinition = "text")
	private String sourceCommitMessage;

	@Column(name = "source_commit_committed_at")
	private Instant sourceCommitCommittedAt;

	@Column(name = "supersedes_submission_id", updatable = false)
	private UUID supersedesSubmissionId;

	@Column(name = "submitted_by", nullable = false, updatable = false)
	private UUID submittedBy;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 100)
	private SubmissionStatus status;

	/**
	 * GitHub 제출은 {@code repository_verification.requested_at}에서 가져온다.
	 * 이 값을 나중 시각으로 잡으면 마감 직전 제출이 LATE/MISSED로 잘못 판정된다.
	 */
	@Column(name = "submitted_at", nullable = false, updatable = false)
	private Instant submittedAt;

	@Column(name = "is_current", nullable = false)
	private boolean current;

	/** ck_submission_status_2: FETCH_FAILED·INVALID일 때만 NOT NULL, VALIDATING·ACCEPTED일 때는 NULL. */
	@Column(name = "failure_reason", columnDefinition = "text")
	private String failureReason;

	@Column(name = "repository_verification_id", updatable = false)
	private UUID repositoryVerificationId;

	private Submission(
			UUID orgId,
			UUID teamId,
			UUID assessmentRoundId,
			SubmissionMethod method,
			String requestedBranch,
			UUID supersedesSubmissionId,
			UUID submittedBy,
			SubmissionStatus status,
			Instant submittedAt,
			UUID repositoryVerificationId
	) {
		this.orgId = orgId;
		this.teamId = teamId;
		this.assessmentRoundId = assessmentRoundId;
		this.method = method;
		this.requestedBranch = requestedBranch;
		this.supersedesSubmissionId = supersedesSubmissionId;
		this.submittedBy = submittedBy;
		this.status = status;
		this.submittedAt = submittedAt;
		this.current = true;
		this.repositoryVerificationId = repositoryVerificationId;
	}

	/**
	 * GitHub URL 제출을 접수한다. 저장소 접근은 마감 후 분석 배치가 수행하므로 여기서는 즉시 ACCEPTED다.
	 * 정의서: "제출 접수는 URL 형식·호스트 검사만 통과하면 status=ACCEPTED이며 저장소 접근 실패는 분석 단계의 사건이다."
	 */
	public static Submission acceptGithubUrl(
			UUID orgId,
			UUID teamId,
			UUID assessmentRoundId,
			String requestedBranch,
			UUID supersedesSubmissionId,
			UUID submittedBy,
			Instant submittedAt,
			UUID repositoryVerificationId
	) {
		return new Submission(
				orgId,
				teamId,
				assessmentRoundId,
				SubmissionMethod.GITHUB_URL,
				requestedBranch,
				supersedesSubmissionId,
				submittedBy,
				SubmissionStatus.ACCEPTED,
				submittedAt,
				repositoryVerificationId
		);
	}

	/**
	 * ZIP 업로드를 접수한다. 내용 검증(EMPTY_CODE·GIT_LOG_MISSING)과 안전 추출이 아직 남아 있어 VALIDATING으로 둔다.
	 * ck_submission_method_2가 ZIP 분기에서 저장소·커밋 컬럼 전부를 NULL로 요구하므로 그대로 비워 둔다.
	 */
	public static Submission receiveZipUpload(
			UUID orgId,
			UUID teamId,
			UUID assessmentRoundId,
			UUID supersedesSubmissionId,
			UUID submittedBy,
			Instant submittedAt
	) {
		return new Submission(
				orgId,
				teamId,
				assessmentRoundId,
				SubmissionMethod.ZIP_WITH_GITLOG,
				null,
				supersedesSubmissionId,
				submittedBy,
				SubmissionStatus.VALIDATING,
				submittedAt,
				null
		);
	}

	/** 재제출로 밀려날 때 호출한다. uq_submission_current 때문에 새 행 INSERT와 같은 트랜잭션이어야 한다. */
	public void supersede() {
		this.current = false;
	}
}
