package com.bigproject.backend.domain.submission.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = """
		코드 분석 결과. 분석이 성공(SUCCEEDED·PARTIAL)한 제출에만 존재한다.

		진행 상태와 실패 사유는 `GET /submissions/{submissionId}/analysis`가 담당한다 — 이쪽은 결과가
		있을 때만 부르는 API다.""")
public record SubmissionAnalysisResultResponse(

		UUID submissionId,

		UUID analysisId,

		@Schema(description = "AI가 실제로 적용한 추출 범위. 요청 범위와 다르면 scopeFallback이 true다.",
				example = "TOTAL")
		String appliedScope,

		@Schema(description = """
				요청한 범위보다 넓게 분석됐다는 뜻이다. true이면 개인 커밋 기준 결과로 볼 수 없으므로
				화면에 경고를 띄워야 한다.""")
		boolean scopeFallback,

		String fallbackReason,

		@Schema(description = "AI가 실제로 분석한 브랜치. ZIP 제출은 null이다.")
		String resolvedBranch,

		HeadCommit headCommit,

		Instant analyzedAt,

		@Schema(description = "문제 슬롯. 근거를 찾지 못한 슬롯도 NOT_GENERATED로 함께 온다.")
		List<Problem> problems,

		@Schema(description = "요구사항 P/F 판정.")
		List<RequirementResult> requirementResults,

		@Schema(description = """
				조회한 교육생 본인의 세션. 분석이 끝나면 READY로 열려 있다. 팀원 각자 다른 세션을 가지므로
				같은 제출을 조회해도 이 값만 사람마다 다르다.""")
		Session session
) {

	public record HeadCommit(String commitHash, String commitMessage, Instant committedAt) {
	}

	@Schema(description = """
			문제 하나. `generationStatus=NOT_GENERATED`이면 코드 근거를 찾지 못해 문항을 만들지 못한 슬롯이고,
			화면에는 `―`로 표시한다. **0단(물어봤는데 못 풀었음)과 다르다.**""")
	public record Problem(
			int problemNo,
			@Schema(allowableValues = {"GENERATED", "NOT_GENERATED"}) String generationStatus,
			@Schema(description = "문항을 만들지 못한 사유. GENERATED면 null", nullable = true)
			String notGeneratedReason,
			@Schema(description = "문제 제목. NOT_GENERATED면 null", nullable = true) String title,
			@Schema(description = "문제 유형. 예: DESIGN_CHOICE", nullable = true) String problemType,
			@Schema(nullable = true) String codeLanguage,
			@Schema(nullable = true) String sourcePath,
			@Schema(nullable = true) Integer lineStart,
			@Schema(nullable = true) Integer lineEnd,
			@Schema(description = "문제 출제에 쓰인 코드 원문. NOT_GENERATED 슬롯은 null이다.", nullable = true)
			String codeSnippet
	) {
	}

	public record RequirementResult(
			String requirementKey,
			String title,
			@Schema(description = "PENDING · PASS · FAIL", example = "PASS")
			String result,
			String evidence,
			@Schema(description = "AI 판정이면 true, 사람이 판정했으면 false다.")
			boolean judgedByAi
	) {
	}

	public record Session(
			UUID sessionId,
			@Schema(description = "READY · IN_PROGRESS · PAUSED · COMPLETED 등", example = "READY")
			String status,
			@Schema(description = "이 세션에 깔린 문제 단계 수. 문항 3개면 12(3 × 4축)다.")
			int stageCount
	) {
	}
}
