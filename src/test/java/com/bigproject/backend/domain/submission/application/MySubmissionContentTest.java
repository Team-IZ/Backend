package com.bigproject.backend.domain.submission.application;

import com.bigproject.backend.domain.submission.domain.MySubmissionQueryRepository;
import com.bigproject.backend.domain.submission.domain.MySubmissionQueryRepository.MySubmissionRow;
import com.bigproject.backend.domain.submission.presentation.dto.MySubmissionResponse;
import com.bigproject.backend.domain.submission.presentation.dto.MySubmissionResponse.SubmissionContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * `제출한 내용` 카드가 <b>두 수단에서 각각 무엇을 담는가</b>를 못 박는다.
 *
 * <p>종전에는 GitHub 제출에서만 {@code content}를 만들어, ZIP으로 낸 학생은 자기가 무엇을 냈는지
 * 화면에서 볼 수 없었다(20차 R4). 응답은 200이고 화면도 서기 때문에 어디서도 드러나지 않는다 —
 * 카드에 채울 것이 없다는 사실만 학생 쪽에 남는다.
 */
class MySubmissionContentTest {

	private static final UUID PROJECT = UUID.randomUUID();
	private static final UUID USER = UUID.randomUUID();
	private static final UUID ROUND = UUID.randomUUID();
	private static final UUID SUBMISSION = UUID.randomUUID();
	private static final Instant COMMITTED_AT = Instant.parse("2026-02-19T10:00:00Z");

	private MySubmissionQueryRepository queryRepository;
	private MySubmissionService service;

	@BeforeEach
	void setUp() {
		queryRepository = mock(MySubmissionQueryRepository.class);
		service = new MySubmissionService(queryRepository);
	}

	@Test
	@DisplayName("ZIP 제출은 파일 이름·크기를 담고 저장소 필드는 비운다")
	void zipSubmissionCarriesFileNameAndSize() {
		SubmissionContent content = contentOf(zipRow("team3-miniproject.zip", 12_873_421L, "a3f9c21"));

		assertThat(content).isNotNull();
		assertThat(content.fileName()).isEqualTo("team3-miniproject.zip");
		assertThat(content.fileSize()).isEqualTo(12_873_421L);
		assertThat(content.repoUrl()).isNull();
		assertThat(content.branch()).isNull();
	}

	/**
	 * ZIP의 커밋은 제출 행이 아니라 분석 결과에서 온다 — {@code ck_submission_method_2}가 ZIP 분기의
	 * {@code source_commit_*}를 NULL로 강제하기 때문이다.
	 */
	@Test
	@DisplayName("ZIP 제출도 분석이 끝나면 커밋 줄이 붙는다")
	void zipSubmissionShowsTheCommitOnceAnalysisSucceeded() {
		assertThat(contentOf(zipRow("a.zip", 1L, "a3f9c21")).lastCommit())
				.satisfies(commit -> {
					assertThat(commit.sha()).isEqualTo("a3f9c21");
					assertThat(commit.at()).isEqualTo(COMMITTED_AT);
				});
	}

	@Test
	@DisplayName("분석 전 ZIP 제출은 커밋 키가 빠진다 — git log를 읽는 주체가 AI다")
	void zipSubmissionHasNoCommitBeforeAnalysis() {
		assertThat(contentOf(zipRow("a.zip", 1L, null)).lastCommit()).isNull();
	}

	@Test
	@DisplayName("GitHub 제출은 종전처럼 저장소·브랜치를 담고 파일 필드는 비운다")
	void githubSubmissionStillCarriesRepositoryAndBranch() {
		SubmissionContent content = contentOf(githubRow());

		assertThat(content.repoUrl()).isEqualTo("https://github.com/team3/mini");
		assertThat(content.branch()).isEqualTo("main");
		assertThat(content.fileName()).isNull();
		assertThat(content.fileSize()).isNull();
	}

	/** 아티팩트 행이 아직 없는 접수 도중에는 빈 카드를 만들지 않는다. */
	@Test
	@DisplayName("ZIP인데 아티팩트가 없으면 content 키 자체가 빠진다")
	void zipSubmissionWithoutArtifactHasNoContent() {
		assertThat(contentOf(zipRow(null, null, null))).isNull();
	}

	// ------------------------------------------------------------------ fixture

	private SubmissionContent contentOf(MySubmissionRow row) {
		when(queryRepository.findMySubmission(any(), any())).thenReturn(Optional.of(row));
		MySubmissionResponse response = service.getMySubmission(PROJECT, USER);
		return response.content();
	}

	private MySubmissionRow zipRow(String fileName, Long fileSize, String commitSha) {
		return row("ZIP_WITH_GITLOG", null, null, null, null, fileName, fileSize, commitSha);
	}

	private MySubmissionRow githubRow() {
		return row("GITHUB_URL", "https://github.com/team3/mini", "main", "c0ffee", "feat: 결제",
				null, null, null);
	}

	private MySubmissionRow row(String method, String repoUrl, String resolvedBranch,
			String commitSha, String commitMessage, String fileName, Long fileSize,
			String analysisCommitSha) {
		return new MySubmissionRow(
				ROUND, "미프 1차", "OPEN", Instant.parse("2026-02-20T00:00:00Z"),
				SUBMISSION, method, "ACCEPTED", Instant.parse("2026-02-19T00:00:00Z"), null,
				repoUrl, null, resolvedBranch, null,
				commitSha, commitMessage, commitSha == null ? null : COMMITTED_AT,
				fileName, fileSize,
				"SUCCEEDED", null, Instant.parse("2026-02-19T01:00:00Z"),
				analysisCommitSha, analysisCommitSha == null ? null : "feat: 결제 롤백 처리",
				analysisCommitSha == null ? null : COMMITTED_AT,
				false, Instant.parse("2026-02-21T00:00:00Z"));
	}
}
