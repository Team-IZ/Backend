package com.bigproject.backend.domain.codeanalysis.application;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /analyses/{jobId}}의 {@code result}. 성공·부분성공일 때만 온다.
 *
 * <p><b>AI 스키마의 이름을 그대로 쓴다.</b> 우리 컬럼명(예: {@code source_line_start})으로 미리 바꾸면
 * AI 응답과 이 파일을 나란히 놓고 대조할 수 없게 되고, 스키마가 바뀌었을 때 어디가 어긋났는지 찾는
 * 비용이 커진다. DB 컬럼으로의 변환은 적재하는 자리에서 한 번에 한다.
 *
 * <p>{@code analysisDocument}만 {@link JsonNode}다. 통째로 {@code code_analysis.analysis_document}
 * JSONB에 넣을 값이라 필드를 하나씩 풀 이유가 없고, 풀어 두면 AI가 문서 구조를 넓힐 때마다 여기도
 * 따라 고쳐야 한다. {@code schemaVersion}이 그 변화를 감당하는 자리다.
 */
public record AnalysisResultPayload(
		String snapshotId,
		SnapshotMeta snapshotMeta,
		String appliedScope,
		Boolean scopeFallback,
		String fallbackReason,
		String commitSha,
		JsonNode analysisDocument,
		List<RequirementResult> requirementResults,
		List<Problem> problems,
		List<UnmatchedTeach> unmatchedTeaches,
		Integer questionCountPlanned,
		String resolvedBranch,
		HeadCommit headCommit,
		List<GitCommit> gitHistory,
		String gitHistorySource,
		Boolean historyTruncated
) {

	public record SnapshotMeta(String contentHash, Integer fileCount, Long byteCount) {
	}

	/** 요구사항 P/F 판정. {@code requirementId}는 우리가 요청에 실어 보낸 값이 그대로 돌아온다. */
	public record RequirementResult(String requirementId, String verdict, String evidence, String note) {
	}

	public record Problem(
			String problemId,
			Integer problemNo,
			String problemType,
			Double priority,
			String questionFocusItemId,
			String title,
			String snippetKey,
			String codeLanguage,
			String sourcePath,
			Integer lineStart,
			Integer lineEnd,
			String codeSnippet,
			String contentHash,
			String evidenceHash,
			Integer extractorVersion,
			String teachId,
			List<ProblemReference> references,
			List<ProblemStage> stages
	) {
	}

	public record ProblemReference(
			String referenceType,
			Integer displayOrder,
			String path,
			Integer lineStart,
			Integer lineEnd,
			String axisCode,
			String teachId,
			String evidenceHash
	) {
	}

	/**
	 * 4축 각각의 동결된 질문과 힌트 2개.
	 *
	 * <p>여기까지 받아 두지만 <b>이번 단계에서는 저장하지 않는다.</b> {@code problem_stage.session_id}가
	 * NOT NULL이라 {@code measurement_attempt} → {@code assessment_session}을 먼저 만들어야 하고,
	 * 그건 다음 단계다.
	 */
	public record ProblemStage(String axisCode, String questionText, Boolean flagged, List<Hint> hints) {
	}

	public record Hint(Integer hintLevel, String hintText) {
	}

	/** 문항을 만들지 못한 검증 개념. NOT_GENERATED 문제 슬롯의 근거가 된다. */
	public record UnmatchedTeach(String teachId, String reason) {
	}

	public record HeadCommit(String commitHash, String commitMessage, Instant committedAt) {
	}

	public record GitCommit(
			String commitHash,
			String commitMessage,
			String authorName,
			String authorEmail,
			Instant committedAt,
			List<String> changedFiles,
			Integer additions,
			Integer deletions,
			String parentSha,
			Instant authoredAt,
			String branchName,
			Boolean isMergeCommit,
			Boolean isRevertCommit,
			Boolean isBotCommit,
			Integer changedLineCount
	) {
	}
}
