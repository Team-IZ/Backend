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
	 * GitHub 제출은 접수 시점에 채운다. 팀의 ACTIVE {@code repository} 행을 가리키며, 제출된 URL은
	 * 그 행이 보관한다(S-12).
	 *
	 * <p>같은 팀이 재제출로 주소를 바꾸면 그 행의 URL이 갱신되므로, <b>과거 제출이 이 FK로 따라가면
	 * 지금의 URL을 보게 된다.</b> 제출 시점의 URL이 필요하면 분석 때 만들어지는
	 * {@code repository_verification}을 봐야 한다.
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
	 * 교육생이 제출을 요청한 시각이다. 서버가 요청을 받은 시점으로 확정하며 이후 어떤 외부 처리에도
	 * 영향받지 않는다(S-12). 마감 대비 ON_TIME/LATE/MISSED 판정의 기준이라, 저장소 확인이나 분석에
	 * 걸린 시간이 이 값을 밀면 마감 직전 제출이 LATE로 잘못 판정된다.
	 */
	@Column(name = "submitted_at", nullable = false, updatable = false)
	private Instant submittedAt;

	@Column(name = "is_current", nullable = false)
	private boolean current;

	/** ck_submission_status_2: FETCH_FAILED·INVALID일 때만 NOT NULL, VALIDATING·ACCEPTED일 때는 NULL. */
	@Column(name = "failure_reason", columnDefinition = "text")
	private String failureReason;

	/**
	 * 분석 배치가 저장소 확인을 시작할 때 채운다. 제출 시점에는 NULL이다.
	 *
	 * <p>{@code updatable = false}가 아니다. {@code repository_verification.requested_at}이 "확인 시작
	 * 시각"으로 재정의되면서(S-12) 그 행이 제출이 아니라 분석 시점에 만들어지기 때문이다.
	 */
	@Column(name = "repository_verification_id")
	private UUID repositoryVerificationId;

	/**
	 * 클라이언트가 보낸 제출 요청 멱등키. GitHub·ZIP이 같은 자리를 쓴다(S-13).
	 * {@code uq_submission_request_idempotency_key}가 같은 키의 동시 재시도까지 막는다.
	 */
	@Column(name = "request_idempotency_key", updatable = false)
	private UUID requestIdempotencyKey;

	private Submission(
			UUID orgId,
			UUID teamId,
			UUID assessmentRoundId,
			SubmissionMethod method,
			UUID repositoryId,
			String requestedBranch,
			UUID supersedesSubmissionId,
			UUID submittedBy,
			SubmissionStatus status,
			Instant submittedAt,
			UUID requestIdempotencyKey
	) {
		this.orgId = orgId;
		this.teamId = teamId;
		this.assessmentRoundId = assessmentRoundId;
		this.method = method;
		this.repositoryId = repositoryId;
		this.requestedBranch = requestedBranch;
		this.supersedesSubmissionId = supersedesSubmissionId;
		this.submittedBy = submittedBy;
		this.status = status;
		this.submittedAt = submittedAt;
		this.current = true;
		this.requestIdempotencyKey = requestIdempotencyKey;
	}

	/**
	 * GitHub URL 제출을 접수한다. 저장소 접근은 마감 후 분석 배치가 수행하므로 여기서는 즉시 ACCEPTED다.
	 * 정의서: "제출 접수는 URL 형식·호스트 검사만 통과하면 status=ACCEPTED이며 저장소 접근 실패는 분석 단계의 사건이다."
	 */
	public static Submission acceptGithubUrl(
			UUID orgId,
			UUID teamId,
			UUID assessmentRoundId,
			UUID repositoryId,
			String requestedBranch,
			UUID supersedesSubmissionId,
			UUID submittedBy,
			Instant submittedAt,
			UUID requestIdempotencyKey
	) {
		return new Submission(
				orgId,
				teamId,
				assessmentRoundId,
				SubmissionMethod.GITHUB_URL,
				repositoryId,
				requestedBranch,
				supersedesSubmissionId,
				submittedBy,
				SubmissionStatus.ACCEPTED,
				submittedAt,
				requestIdempotencyKey
		);
	}

	/**
	 * ZIP 업로드를 접수한다. ck_submission_method_2가 ZIP 분기에서 저장소·커밋 컬럼 전부를 NULL로 요구하므로
	 * 그대로 비워 둔다.
	 *
	 * <p><b>ACCEPTED로 둔다(2026-08-09).</b> 종전에는 VALIDATING이었다 — 내용 검증
	 * (EMPTY_CODE·GIT_LOG_MISSING)이 남아 있다는 이유였는데, 그 판정의 주체가 AI로 확정됐다.
	 * 세 코드 모두 {@code analysis_job.failure_code} 값 집합에 있고 AI가 분석 중에 돌려준다.
	 * 즉 <b>백엔드가 더 할 검증이 없다</b> — 크기와 압축 형식은 접수 시점에 이미 봤다.
	 * VALIDATING으로 두면 아무도 다음 상태로 옮겨 주지 않아 제출이 영원히 그 자리에 머물고,
	 * 분석 대상 조회({@code status='ACCEPTED'})에도 걸리지 않아 ZIP 제출은 분석 자체가 안 된다.
	 *
	 * <p>⚠️ {@code submission_artifact.validation_status}는 VALIDATING으로 남는다. 그쪽 VERIFIED는
	 * {@code safe_extract_uri}를 요구하는데 안전 추출은 실제로 구현돼 있지 않다 — 상태만 올리면
	 * 하지 않은 일을 했다고 기록하는 것이 된다. 정의서의 "ACCEPTED 시점에 검증 완료 artifact 1건"
	 * 서술과 어긋나므로 정의서 쪽 주석도 함께 고쳤다.
	 */
	public static Submission receiveZipUpload(
			UUID orgId,
			UUID teamId,
			UUID assessmentRoundId,
			UUID supersedesSubmissionId,
			UUID submittedBy,
			Instant submittedAt,
			UUID requestIdempotencyKey
	) {
		return new Submission(
				orgId,
				teamId,
				assessmentRoundId,
				SubmissionMethod.ZIP_WITH_GITLOG,
				null,
				null,
				supersedesSubmissionId,
				submittedBy,
				SubmissionStatus.ACCEPTED,
				submittedAt,
				requestIdempotencyKey
		);
	}

	/** 재제출로 밀려날 때 호출한다. uq_submission_current 때문에 새 행 INSERT와 같은 트랜잭션이어야 한다. */
	public void supersede() {
		this.current = false;
	}
}
