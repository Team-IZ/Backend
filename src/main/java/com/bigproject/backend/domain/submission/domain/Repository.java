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
 * repository 테이블 매핑 엔티티. 팀이 현재 쓰는 GitHub 저장소 1건이다.
 *
 * <p><b>팀당 ACTIVE 1건이다.</b> {@code uq_repository_active_per_team (team_id) WHERE status='ACTIVE'}
 * 때문에 재제출로 주소가 바뀌어도 새 행을 만들 수 없다. 기존 행의 URL을 {@link #changeRepositoryUrl}로
 * 갱신한다. 그래서 이 엔티티는 "제출 이력"이 아니라 "지금 이 팀의 저장소"를 뜻한다.
 *
 * <p>덮어쓴 이전 URL은 남지 않는다. 제출별 URL 이력이 필요하면 분석 시점에 만들어지는
 * {@code repository_verification}을 봐야 한다.
 *
 * <p>GitHub 메타 4종({@code externalRepositoryId}·{@code ownerLogin}·{@code repositoryName}·
 * {@code defaultBranch})은 GitHub API에서만 나오는데 백엔드는 GitHub에 접근하지 않는다. AI 분석 응답이
 * 주면 채우고 아니면 NULL로 둔다(S-10). 이 클래스는 그 컬럼들을 매핑만 하고 채우지 않는다.
 */
@Getter
@Entity
@Table(name = "repository")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Repository {

	private static final String PROVIDER_GITHUB = "GITHUB";

	@Id
	@UuidGenerator
	@Column(name = "repository_id", updatable = false, nullable = false)
	private UUID repositoryId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "team_id", nullable = false, updatable = false)
	private UUID teamId;

	@Column(name = "org_id", nullable = false, updatable = false)
	private UUID orgId;

	@Column(name = "provider", nullable = false, updatable = false, length = 100)
	private String provider;

	/** GitHub API에서만 얻는 값이라 분석 전까지 NULL이다(S-10). */
	@Column(name = "external_repository_id")
	private UUID externalRepositoryId;

	@Column(name = "owner_login", columnDefinition = "text")
	private String ownerLogin;

	@Column(name = "repository_name", length = 200)
	private String repositoryName;

	@Column(name = "repo_url", nullable = false, columnDefinition = "text")
	private String repoUrl;

	@Column(name = "normalized_repo_url", nullable = false, columnDefinition = "text")
	private String normalizedRepoUrl;

	/** 저장소의 기본 브랜치다. 교육생이 요청한 브랜치가 아니다 — 그쪽은 submission.requested_branch가 갖는다. */
	@Column(name = "default_branch", columnDefinition = "text")
	private String defaultBranch;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private RepositoryStatus status;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	private Repository(UUID projectId, UUID teamId, UUID orgId, String repoUrl, String normalizedRepoUrl,
			Instant updatedAt) {
		this.projectId = projectId;
		this.teamId = teamId;
		this.orgId = orgId;
		this.provider = PROVIDER_GITHUB;
		this.repoUrl = repoUrl;
		this.normalizedRepoUrl = normalizedRepoUrl;
		this.status = RepositoryStatus.ACTIVE;
		this.updatedAt = updatedAt;
	}

	/**
	 * 팀이 처음 GitHub 주소를 제출할 때 만든다.
	 *
	 * <p>{@code ck_repository_provider}가 GITHUB만 허용하므로 provider는 상수다.
	 */
	public static Repository active(UUID projectId, UUID teamId, UUID orgId,
			String repoUrl, String normalizedRepoUrl, Instant updatedAt) {
		return new Repository(projectId, teamId, orgId, repoUrl, normalizedRepoUrl, updatedAt);
	}

	/**
	 * 재제출로 주소가 바뀌었을 때 호출한다.
	 *
	 * <p>새 행을 만들지 않는 이유는 {@code uq_repository_active_per_team}이 팀당 ACTIVE 1건을 강제하기
	 * 때문이다. 기존 행을 INACTIVE로 내리고 새 행을 넣는 방법도 있지만, 그러면 과거 제출이 가리키던
	 * {@code repository_id}가 INACTIVE 행을 가리키게 되어 "이 팀의 현재 저장소" 의미가 흐려진다.
	 *
	 * <p>GitHub 메타 4종은 함께 지운다. 주소가 달라졌으면 이전 저장소에서 받은 식별자·기본 브랜치가
	 * 더 이상 이 행을 설명하지 못한다. 남겨 두면 새 주소에 옛 저장소의 메타가 붙은 거짓 행이 된다.
	 */
	public void changeRepositoryUrl(String repoUrl, String normalizedRepoUrl, Instant updatedAt) {
		if (this.normalizedRepoUrl.equals(normalizedRepoUrl)) {
			this.repoUrl = repoUrl;
			this.updatedAt = updatedAt;
			return;
		}
		this.repoUrl = repoUrl;
		this.normalizedRepoUrl = normalizedRepoUrl;
		this.externalRepositoryId = null;
		this.ownerLogin = null;
		this.repositoryName = null;
		this.defaultBranch = null;
		this.updatedAt = updatedAt;
	}
}
