package com.bigproject.backend.domain.submission.presentation.dto;

import com.bigproject.backend.domain.submission.domain.SubmissionMethod;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * TR-02 `제출` 화면 전체를 그리는 한 응답.
 *
 * <p><b>상태를 서버가 하나로 판정한다.</b> 화면이 *제출했나 · 분석 끝났나 · 세션 시작했나 · 마감 지났나* 를
 * 조합하지 않게 하려는 것이 이 API의 요점이다 — 조합하면 같은 판정 규칙이 화면과 서버 양쪽에 생기고,
 * 규칙이 바뀔 때 반드시 한쪽만 바뀐다.
 *
 * <p>상태별로 쓰지 않는 필드는 {@link JsonInclude}로 <b>키 자체가 빠진다.</b> null을 실어 보내면
 * "`ANALYZING`인데 `verifyClosesAt`이 있으면 무슨 뜻인가"를 화면이 매번 판단하게 된다.
 */
@Schema(description = "내 팀의 현재 제출 상태")
public record MySubmissionResponse(

		@Schema(description = "회차 식별자") String assessmentRoundId,

		@Schema(description = "운영자가 붙인 회차 이름 그대로", example = "미프 3차") String roundLabel,

		@Schema(description = "제출 마감") Instant submissionDueAt,

		@Schema(description = """
				`DRAFT`(아직 제출 안 함 → 폼) · `ANALYZING`(제출됨·분석 중) ·
				`READY`(분석 완료 — **아직 다시 제출할 수 있다**) · `LOCKED`(세션을 시작해 잠김) ·
				`ANALYSIS_FAILED`(분석 실패 → 폼을 이전 값으로 되채운다) · `SUBMISSION_CLOSED`(마감 지남)""",
				allowableValues = {"DRAFT", "ANALYZING", "READY", "LOCKED", "ANALYSIS_FAILED",
						"SUBMISSION_CLOSED"})
		String status,

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = "제출 식별자. 분석 폴링(`GET /submissions/{id}/analysis`)에 쓴다. 미제출이면 키가 빠진다")
		String submissionId,

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = "제출 수단. 미제출이면 키가 빠진다",
				implementation = SubmissionMethod.class) String method,

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = "제출 시각. 제출 후에만. 미제출이면 키가 빠진다") Instant submittedAt,

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = "분석 완료 시각. 분석 후에만. 그 전에는 키가 빠진다") Instant analyzedAt,

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = "개인 응시 창 종료. `READY`·`LOCKED`에서만. 그 외에는 키가 빠진다") Instant verifyClosesAt,

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = """
				사용자에게 보일 실패 사유. `ANALYSIS_FAILED`에서만.
				`failureCode`가 기계용이고 이쪽이 문구다 — 화면이 코드로 문구를 만들지 않는다.
				그 외에는 키가 빠진다""")
		String failureReason,

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = """
				실패 코드 15종. `REPO_NOT_FOUND`·`REPOSITORY_ACCESS_DENIED`면 화면이 ZIP 전환을 안내한다.
				`ANALYSIS_FAILED`에서만이고 그 외에는 키가 빠진다""",
				allowableValues = {"SOURCE_UNREACHABLE", "UNSUPPORTED_LANGUAGE", "ANALYSIS_TIMEOUT",
						"MODEL_ERROR", "TEMPORARY_ERROR", "INVALID_REPOSITORY_URL", "REPO_NOT_FOUND",
						"REPOSITORY_ACCESS_DENIED", "BRANCH_NOT_FOUND", "UNSUPPORTED_HOST",
						"FILE_TOO_LARGE", "ARCHIVE_INVALID", "EMPTY_CODE", "PROHIBITED_FILE",
						"GIT_LOG_MISSING"})
		String failureCode,

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = """
				제출 내용. **제출 수단에 따라 둘 중 하나**이고, 미제출이면 키가 빠진다.

				`repoUrl`이 있으면 저장소 행을, `fileName`이 있으면 파일 행을 그리면 된다.""",
				oneOf = {GithubSubmissionContent.class, ZipSubmissionContent.class})
		SubmissionContent content
) {

	/**
	 * 제출한 것. <b>화면 `제출한 내용` 카드 하나를 두 수단이 함께 쓰지만, 담기는 값은 배타적이다.</b>
	 *
	 * <p>종전에는 다섯 필드를 한 스키마에 담고 전부 선택으로 두었다. 그러면 <b>"둘 다 없을 수도 있다"가
	 * 타입이 되어</b> 서버가 한쪽을 빠뜨려도 화면이 컴파일된다(23차 R1). 타입을 둘로 가르면 화면이
	 * {@code 'repoUrl' in content}로 갈라야 하고, 서버도 한쪽을 빠뜨리면 스키마 검증에 걸린다.
	 *
	 * <p>ZIP 분기가 저장소·브랜치를 갖지 않는 것은 {@code ck_submission_method_2}가 그 컬럼들을
	 * NULL로 강제하기 때문이다. 반대로 <b>ZIP의 커밋 정보는 제출 행이 아니라 분석 결과에서 온다</b>
	 * ({@code code_analysis.head_commit_*}) — git log를 읽는 주체가 AI라 분석 전에는 알 수 없다.
	 */
	@Schema(hidden = true)
	public sealed interface SubmissionContent permits GithubSubmissionContent, ZipSubmissionContent {
	}

	/**
	 * GitHub 제출.
	 *
	 * @param branch 실제로 분석된 브랜치. 분석 전에는 교육생이 적어 낸 값이고, 분석 후에는 AI가 확정한
	 *               값이다. 교육생이 비워 냈고 저장소 기본 브랜치도 아직 모르면 <b>{@code null}이다</b> —
	 *               키는 항상 오므로 화면은 값의 유무만 보면 된다
	 */
	@Schema(name = "GithubSubmissionContent", description = "GitHub 저장소로 낸 제출")
	public record GithubSubmissionContent(
			@Schema(description = "교육생이 입력한 원문 주소. 정규화 전 값이라 폼에 그대로 되채울 수 있다",
					example = "https://github.com/team3/mini")
			String repoUrl,
			@Schema(description = "브랜치. 아직 확정되지 않았으면 null", example = "main", nullable = true)
			String branch,
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(description = "분석 대상 커밋. 분석 성공 후에만 채워지고 그 전에는 키가 빠진다")
			LastCommit lastCommit
	) implements SubmissionContent {
	}

	/**
	 * ZIP 업로드 제출.
	 *
	 * <p>두 값은 {@code submission_artifact} 한 행에서 온다. 그 행이 없으면 채울 수 있는 것이 없으므로
	 * <b>{@code content} 키 자체가 빠진다</b> — 빈 카드를 만들지 않는다.
	 */
	@Schema(name = "ZipSubmissionContent", description = "ZIP 파일로 낸 제출")
	public record ZipSubmissionContent(
			@Schema(description = "올린 파일 이름", example = "team3-miniproject.zip")
			String fileName,
			@Schema(description = "올린 파일 크기(바이트)", example = "12873421")
			Long fileSize,
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(description = """
					분석 대상 커밋. 분석 성공 후에만 채워지고 그 전에는 키가 빠진다.
					ZIP 제출도 AI가 git log를 읽어 채운다""")
			LastCommit lastCommit
	) implements SubmissionContent {
	}

	/** 분석이 실제로 읽은 커밋. 교육생이 "내가 낸 그 코드인가"를 확인하는 값이다. */
	public record LastCommit(
			@Schema(example = "a3f9c21") String sha,
			String message,
			@Schema(description = "커밋 시각") Instant at
	) {
	}
}
