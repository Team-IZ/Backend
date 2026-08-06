package com.bigproject.backend.domain.submission.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 팀당 ACTIVE 저장소가 1건뿐이라({@code uq_repository_active_per_team}) 재제출은 새 행이 아니라
 * 기존 행의 주소 갱신으로 처리된다. 그 갱신이 무엇을 남기고 무엇을 지우는지 고정한다.
 */
class RepositoryTest {

	private static final Instant FIRST = Instant.parse("2026-08-06T09:00:00Z");
	private static final Instant SECOND = Instant.parse("2026-08-06T10:00:00Z");

	private Repository analyzed() {
		Repository repository = Repository.active(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				"https://github.com/Team-IZ/Mini-Project-3", "https://github.com/team-iz/mini-project-3", FIRST);
		// 분석이 성공하면 AI 응답으로 채워지는 값들을 흉내낸다.
		setAnalysisMetadata(repository);
		return repository;
	}

	private void setAnalysisMetadata(Repository repository) {
		// 엔티티에 setter 를 두지 않았으므로 분석 배치가 채우는 상태를 리플렉션으로 만든다.
		// 프로덕션 코드에 테스트 전용 진입점을 뚫는 것보다 낫다.
		try {
			for (String field : new String[] {"ownerLogin", "repositoryName", "defaultBranch"}) {
				var f = Repository.class.getDeclaredField(field);
				f.setAccessible(true);
				f.set(repository, "채워진값");
			}
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Test
	void keepsAnalysisMetadataWhenOnlyTheCasingChanges() {
		Repository repository = analyzed();

		// 같은 저장소를 대소문자만 다르게 다시 냈다. 정규화하면 같은 값이다.
		repository.changeRepositoryUrl(
				"https://github.com/TEAM-IZ/mini-project-3", "https://github.com/team-iz/mini-project-3", SECOND);

		assertThat(repository.getRepoUrl()).isEqualTo("https://github.com/TEAM-IZ/mini-project-3");
		// 같은 저장소이므로 분석이 알아낸 메타는 여전히 이 행을 설명한다. 지우면 다음 분석까지 공백이 된다.
		assertThat(repository.getOwnerLogin()).isNotNull();
		assertThat(repository.getDefaultBranch()).isNotNull();
		assertThat(repository.getUpdatedAt()).isEqualTo(SECOND);
	}

	@Test
	void clearsAnalysisMetadataWhenTheRepositoryActuallyChanges() {
		Repository repository = analyzed();

		// 다른 저장소를 냈다.
		repository.changeRepositoryUrl(
				"https://github.com/team-iz/other", "https://github.com/team-iz/other", SECOND);

		assertThat(repository.getNormalizedRepoUrl()).isEqualTo("https://github.com/team-iz/other");
		// 이전 저장소에서 받은 식별자·기본 브랜치는 새 주소를 설명하지 못한다. 남기면 거짓 행이 된다.
		assertThat(repository.getExternalRepositoryId()).isNull();
		assertThat(repository.getOwnerLogin()).isNull();
		assertThat(repository.getRepositoryName()).isNull();
		assertThat(repository.getDefaultBranch()).isNull();
	}

	@Test
	void staysActiveSoTheTeamKeepsExactlyOneCurrentRepository() {
		Repository repository = analyzed();
		repository.changeRepositoryUrl("https://github.com/team-iz/other", "https://github.com/team-iz/other", SECOND);

		// INACTIVE 로 내리고 새 행을 만드는 방식이 아니다 -- 과거 제출의 repository_id 가
		// INACTIVE 행을 가리키게 되어 "이 팀의 현재 저장소" 의미가 흐려진다.
		assertThat(repository.getStatus()).isEqualTo(RepositoryStatus.ACTIVE);
	}
}
