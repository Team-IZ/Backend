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
 * repository_verification 테이블 매핑 엔티티. 저장소 URL·브랜치 확인 <b>실행별</b> 이력 원장이다.
 *
 * <p>제출 시점에 이 행을 반드시 만든다. {@code submission.repository_id}가 분석 성공 전까지 NULL이라
 * <b>교육생이 제출한 URL 원문이 남는 자리는 이 테이블의 {@code normalizedRepoUrl}뿐이기 때문이다.</b>
 *
 * <p>주소 수정·브랜치 변경·재확인은 기존 행 UPDATE가 아니라 새 행 INSERT로 남긴다.
 */
@Getter
@Entity
@Table(name = "repository_verification")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepositoryVerification {

	@Id
	@UuidGenerator
	@Column(name = "verification_id", updatable = false, nullable = false)
	private UUID verificationId;

	@Column(name = "org_id", nullable = false, updatable = false)
	private UUID orgId;

	@Column(name = "team_id", nullable = false, updatable = false)
	private UUID teamId;

	/** 유효 Repository 생성 전 URL 검증 단계에서는 NULL이다. 분석 성공 후에 채워진다. */
	@Column(name = "repository_id")
	private UUID repositoryId;

	@Column(name = "normalized_repo_url", nullable = false, updatable = false, columnDefinition = "text")
	private String normalizedRepoUrl;

	/** 교육생이 브랜치를 입력하지 않으면 NULL. AI 서버가 기본 브랜치를 골라 resolvedBranch로 회신한다. */
	@Column(name = "requested_branch", updatable = false, columnDefinition = "text")
	private String requestedBranch;

	@Column(name = "resolved_branch", columnDefinition = "text")
	private String resolvedBranch;

	@Column(name = "head_commit_sha", length = 64)
	private String headCommitSha;

	@Column(name = "head_commit_message", columnDefinition = "text")
	private String headCommitMessage;

	@Column(name = "head_commit_committed_at")
	private Instant headCommitCommittedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private RepositoryVerificationStatus status;

	@Column(name = "failure_code", length = 100)
	private String failureCode;

	/**
	 * 확인 요청 접수 시각. PENDING·CHECKING을 포함한 모든 상태에서 필수다.
	 * GitHub 제출의 {@code submission.submitted_at}을 이 값으로 확정해 외부 확인 지연이 마감 판정을 바꾸지 않게 한다.
	 */
	@Column(name = "requested_at", nullable = false, updatable = false)
	private Instant requestedAt;

	@Column(name = "checked_at")
	private Instant checkedAt;

	@Column(name = "expires_at")
	private Instant expiresAt;

	/**
	 * 멱등키. 같은 값으로 재요청이 오면 기존 결과를 그대로 돌려준다.
	 *
	 * <p>이름이 {@code request_id}가 아닌 이유는 그 컬럼의 정의가 "요청 추적 ID"였기 때문이다.
	 * 추적 ID는 재시도마다 새로 만들어야 하고 멱등키는 같아야 해서 한 컬럼이 둘을 겸할 수 없다.
	 * {@code reminder_dispatch}가 이미 둘을 별도 컬럼으로 나눠 둔 관례를 따랐다.
	 */
	@Column(name = "request_idempotency_key", nullable = false, updatable = false)
	private UUID requestIdempotencyKey;

	private RepositoryVerification(
			UUID orgId,
			UUID teamId,
			String normalizedRepoUrl,
			String requestedBranch,
			Instant requestedAt,
			UUID requestIdempotencyKey
	) {
		this.orgId = orgId;
		this.teamId = teamId;
		this.normalizedRepoUrl = normalizedRepoUrl;
		this.requestedBranch = requestedBranch;
		this.status = RepositoryVerificationStatus.PENDING;
		this.requestedAt = requestedAt;
		this.requestIdempotencyKey = requestIdempotencyKey;
	}

	/**
	 * 제출 접수 시점의 확인 실행을 연다. 아직 GitHub에 접근하지 않았으므로 PENDING이다.
	 *
	 * <p>PENDING·CHECKING에서는 {@code failureCode}·{@code checkedAt}·{@code expiresAt}이 NULL이어야 하므로
	 * 이 생성자는 셋 다 건드리지 않는다.
	 */
	public static RepositoryVerification pending(
			UUID orgId,
			UUID teamId,
			String normalizedRepoUrl,
			String requestedBranch,
			Instant requestedAt,
			UUID requestIdempotencyKey
	) {
		return new RepositoryVerification(
				orgId, teamId, normalizedRepoUrl, requestedBranch, requestedAt, requestIdempotencyKey);
	}
}
