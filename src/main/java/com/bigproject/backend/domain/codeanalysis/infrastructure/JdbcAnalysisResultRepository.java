package com.bigproject.backend.domain.codeanalysis.infrastructure;

import com.bigproject.backend.domain.codeanalysis.application.AnalysisResultPayload;
import com.bigproject.backend.domain.codeanalysis.application.AnalysisResultPayload.GitCommit;
import com.bigproject.backend.domain.codeanalysis.application.AnalysisResultPayload.Problem;
import com.bigproject.backend.domain.codeanalysis.application.AnalysisResultPayload.ProblemReference;
import com.bigproject.backend.domain.codeanalysis.application.AnalysisResultPayload.RequirementResult;
import com.bigproject.backend.domain.codeanalysis.application.AnalysisResultPayload.UnmatchedTeach;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJob;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 분석 결과 본체를 원장에 적재한다.
 *
 * <h2>왜 JPA 엔티티가 아니라 JDBC인가</h2>
 *
 * <p>여기서 쓰는 6개 테이블은 <b>분석 배치가 한 번 쓰고 이후로는 읽기만</b> 하는 원장이다. 엔티티로
 * 만들면 매핑 6벌과 그만큼의 영속성 컨텍스트가 생기는데, 그 대가로 얻는 더티 체킹·지연 로딩을 쓸 자리가
 * 없다. 게다가 이 테이블들의 CHECK 제약이 컬럼 조합을 강하게 묶고 있어(예:
 * {@code ck_assessment_problem_generation_status_2}), 어떤 컬럼을 함께 채우는지가 SQL에 그대로
 * 보이는 편이 안전하다.
 *
 * <p>응시·세션·단계 생성은 {@link JdbcAssessmentSessionPreparer}가 맡는다. 같은 트랜잭션 안에서
 * 두 번 불린다 — 세션 개설은 문제 적재 <b>앞</b>, 단계 적재는 <b>뒤</b>다. 자세한 이유는
 * {@link #record}의 순서 설명 참조.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class JdbcAnalysisResultRepository {

	/** {@code ck_assessment_problem_not_generated_reason_code}의 유일한 값. */
	private static final String NO_MATCHING_CODE_EVIDENCE = "NO_MATCHING_CODE_EVIDENCE";

	/** {@code ck_assessment_problem_problem_no}: 1~3만 허용한다. */
	private static final int MAX_PROBLEM_NO = 3;

	/**
	 * {@code commit_attribution}의 세 상태 컬럼에는 CHECK가 없다. 값 집합의 정본은 시드이며
	 * 현재 쓰이는 조합은 (MATCHED·COMMIT_EMAIL_EXACT·VERIFIED/PENDING)와
	 * (UNMATCHED·COMMIT_EMAIL_EXACT·UNVERIFIED) 셋뿐이다.
	 */
	private static final String ATTRIBUTION_METHOD_EMAIL = "COMMIT_EMAIL_EXACT";

	private final JdbcTemplate jdbc;
	private final JdbcAssessmentSessionPreparer sessionPreparer;
	private final ObjectMapper objectMapper;

	/**
	 * 분석 결과 한 벌을 적재하고 만들어진 {@code code_analysis.analysis_id}를 돌려준다.
	 *
	 * <p>호출부가 {@code @Transactional}로 감싼다. 중간에 실패하면 {@code code_analysis}만 남고
	 * 문제가 없는 상태가 되는데, 그건 "분석은 됐는데 문항이 없다"로 보여 실패보다 나쁘다.
	 *
	 * <h2>순서 (2026-08-10 재배치)</h2>
	 *
	 * <p>분석 → <b>응시·세션</b> → 문제 → 단계 순이다. 종전에는 세션 개설이 문제 적재 뒤에 한 덩어리로
	 * 묶여 있었는데, {@code assessment_session}은 {@code measurement_attempt}만 참조하므로 문제를
	 * 기다릴 이유가 없다. FK가 실제로 요구하는 것은 {@code problem_stage}가 세션과 문제 <b>둘 다</b>
	 * 뒤에 온다는 것뿐이다.
	 *
	 * <p>{@code commit_attribution}·{@code project_requirement_assessment}를 뒤로 미룬 이유는 둘 다
	 * 세션 계보와 무관한 부산물이라, 핵심 연쇄가 위에서 아래로 한 번에 읽히는 편이 낫기 때문이다.
	 */
	public UUID record(AnalysisJob job, AnalysisResultPayload result) {
		UUID analysisId = UUID.randomUUID();

		supersedePreviousAnalysis(job);
		insertCodeAnalysis(analysisId, job, result);
		updateSubmissionAnalysisInput(job, result);

		// 응시를 확보해 SESSION_READY 로 옮기고 세션을 연다. 문제보다 먼저다.
		sessionPreparer.openSessions(job, analysisId);
		insertProblems(analysisId, job, result);
		// 단계는 세션과 문제가 둘 다 있어야 깔린다 -- problem_stage 가 양쪽을 참조한다.
		sessionPreparer.insertStages(job, analysisId, result);

		insertCommitAttributions(analysisId, job, result);
		upsertRequirementAssessments(analysisId, job, result);
		jdbc.update("UPDATE analysis_job SET analysis_id = ? WHERE job_id = ?", analysisId, job.getJobId());

		return analysisId;
	}

	/**
	 * 같은 제출의 이전 ACTIVE 결과를 물린다.
	 *
	 * <p>{@code uq_code_analysis_active(assessment_round_id, team_id, source_submission_id)
	 * WHERE status='ACTIVE'}가 있어, 재분석이면 이 한 줄 없이는 INSERT가 UNIQUE 위반으로 죽는다.
	 * 재분석은 일시적 실패 뒤 재시도에서 실제로 일어난다.
	 */
	private void supersedePreviousAnalysis(AnalysisJob job) {
		jdbc.update("""
				UPDATE code_analysis SET status = 'SUPERSEDED'
				 WHERE assessment_round_id = ? AND team_id = ? AND source_submission_id = ?
				   AND status = 'ACTIVE'
				""", job.getAssessmentRoundId(), job.getTeamId(), job.getSubmissionId());
	}

	private void insertCodeAnalysis(UUID analysisId, AnalysisJob job, AnalysisResultPayload result) {
		jdbc.update("""
				INSERT INTO code_analysis (
				    analysis_id, org_id, assessment_round_id, team_id, source_submission_id,
				    extraction_scope_id, status, analysis_document, scope_fallback, fallback_reason,
				    external_snapshot_id, applied_scope_code,
				    resolved_branch, head_commit_sha, head_commit_message, head_commit_committed_at,
				    git_history_source, history_truncated)
				VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
				analysisId,
				job.getOrgId(),
				job.getAssessmentRoundId(),
				job.getTeamId(),
				job.getSubmissionId(),
				resolveExtractionScopeId(job, result),
				result.analysisDocument() == null ? "{}" : result.analysisDocument().toString(),
				Boolean.TRUE.equals(result.scopeFallback()),
				result.fallbackReason(),
				parseUuidOrNull(result.snapshotId()),
				result.appliedScope(),
				result.resolvedBranch(),
				result.headCommit() == null ? null : result.headCommit().commitHash(),
				result.headCommit() == null ? null : result.headCommit().commitMessage(),
				result.headCommit() == null ? null : toTimestamp(result.headCommit().committedAt()),
				// 커밋 이력의 완전성. 개인 기여도를 "최소값"으로 읽어야 하는지 판단하는 근거다.
				result.gitHistorySource(),
				result.historyTruncated());
	}

	/**
	 * {@code code_analysis.extraction_scope_id}는 NOT NULL이라 반드시 찾아야 한다.
	 *
	 * <p>AI가 실제로 적용한 범위({@code appliedScope})와 같은 {@code scope_code}의 현행 행을 고른다.
	 * 요청 범위와 다르면 {@code scope_fallback=TRUE}로 그 사실이 따로 남으므로, 여기서는 적용된 쪽을
	 * 가리키는 편이 결과와 일치한다.
	 *
	 * <p>못 찾으면 회차의 현행 행 아무거나로 떨어진다. 그래도 없으면 예외다 — NULL을 넣을 수 없어
	 * 어차피 INSERT가 실패하는데, 그때는 원인이 "NOT NULL 위반"으로만 보여 추적이 어렵다.
	 */
	private UUID resolveExtractionScopeId(AnalysisJob job, AnalysisResultPayload result) {
		List<UUID> matched = jdbc.queryForList("""
				SELECT extraction_scope_id FROM project_extraction_scope
				 WHERE assessment_round_id = ? AND effective_to IS NULL
				 ORDER BY (scope_code = ?) DESC, version_no DESC
				 LIMIT 1
				""", UUID.class, job.getAssessmentRoundId(), result.appliedScope());
		if (matched.isEmpty()) {
			throw new IllegalStateException(
					"회차에 현행 project_extraction_scope 가 없어 분석 결과를 적재할 수 없다: assessmentRoundId="
							+ job.getAssessmentRoundId());
		}
		return matched.get(0);
	}

	/**
	 * 제출에 분석 입력을 확정한다.
	 *
	 * <p>{@code ck_submission_analysis_input_captured_at}이 <b>다섯 컬럼을 전부 채우거나 전부 비우거나</b>
	 * 둘 중 하나만 허용한다. 그래서 하나라도 빠지면 UPDATE가 통째로 막힌다.
	 *
	 * <p>{@code resolved_branch}·{@code source_commit_*}는 건드리지 않는다. ZIP 제출에서 그 컬럼들을
	 * NULL로 강제하는 CHECK가 있어(S-20) 브랜치·헤드 커밋은 {@code code_analysis} 쪽에 넣는다.
	 */
	private void updateSubmissionAnalysisInput(AnalysisJob job, AnalysisResultPayload result) {
		AnalysisResultPayload.SnapshotMeta meta = result.snapshotMeta();
		if (meta == null || meta.contentHash() == null) {
			log.warn("snapshotMeta 가 없어 제출의 분석 입력을 확정하지 않는다: submissionId={}", job.getSubmissionId());
			return;
		}
		jdbc.update("""
				UPDATE submission
				   SET analysis_input_hash       = ?,
				       git_history               = ?::jsonb,
				       code_snippets             = ?::jsonb,
				       analysis_input_file_count = ?,
				       analysis_input_byte_count = ?,
				       analysis_input_captured_at = ?
				 WHERE submission_id = ?
				""",
				meta.contentHash(),
				// AI 응답을 그대로 담는다(commitHash·parentSha·branchName 등 전 필드). 질의 대상 사본은
				// commit_attribution 이고 이쪽은 분석 입력 스냅샷이라 줄여 담을 이유가 없다.
				toJson(result.gitHistory() == null ? List.of() : result.gitHistory()),
				toJson(codeSnippets(result)),
				meta.fileCount() == null ? 0 : meta.fileCount(),
				meta.byteCount(),
				Timestamp.from(Instant.now()),
				job.getSubmissionId());
	}

	/**
	 * {@code submission.code_snippets}에 넣을 대표 스니펫.
	 *
	 * <p>{@code ck_submission_code_snippets}가 <b>배열 길이 0~3</b>을 강제한다. AI가 그보다 많이 보낼
	 * 일은 {@code questionBudget}이 3이라 없지만, 넘어오면 잘라야 저장이 막히지 않는다.
	 *
	 * <p>문제 본문이 아니라 스니펫만 담는다 — {@code assessment_problem}은 {@code source_snippet_key}로
	 * 이 배열을 가리키기만 한다(정의서 CodeAnalysis 설명).
	 */
	private List<Map<String, Object>> codeSnippets(AnalysisResultPayload result) {
		List<Map<String, Object>> snippets = new ArrayList<>();
		for (Problem problem : problemsOf(result)) {
			if (snippets.size() >= MAX_PROBLEM_NO) {
				break;
			}
			// 🔴 키 이름은 시드(docs/dummy-data/seed-all-tables.sql)의 모양과 같아야 한다.
			// JSONB 라 스키마가 강제되지 않아, 쓰는 쪽과 읽는 쪽이 다른 이름을 쓰면 조용히 빈 값이 된다.
			// snippetKey·path·lineStart·lineEnd·language·code 는 시드에 이미 있던 이름이고,
			// problemNo·contentHash 는 정의서 컬럼 설명이 요구하는데 시드에 빠져 있어 이번에 함께 넣었다.
			Map<String, Object> snippet = new LinkedHashMap<>();
			snippet.put("snippetKey", problem.snippetKey());
			snippet.put("problemNo", problem.problemNo());
			snippet.put("language", problem.codeLanguage());
			snippet.put("path", problem.sourcePath());
			snippet.put("lineStart", problem.lineStart());
			snippet.put("lineEnd", problem.lineEnd());
			snippet.put("contentHash", problem.contentHash());
			snippet.put("code", problem.codeSnippet());
			snippets.add(snippet);
		}
		return snippets;
	}

	/**
	 * 문제 슬롯을 채운다. 생성된 문제는 GENERATED로, 근거를 못 찾은 개념은 NOT_GENERATED로 남긴다.
	 *
	 * <p>NOT_GENERATED 슬롯을 만드는 이유는 화면의 {@code ―}(문항 없음)와 0단(물어봤는데 못 풀었음)을
	 * 구분하기 위해서다. AI가 {@code unmatchedTeaches}로 그 근거를 준다.
	 */
	private void insertProblems(UUID analysisId, AnalysisJob job, AnalysisResultPayload result) {
		List<Integer> usedNumbers = new ArrayList<>();
		for (Problem problem : problemsOf(result)) {
			UUID problemId = problemIdOf(problem);
			usedNumbers.add(problem.problemNo());
			jdbc.update("""
					INSERT INTO assessment_problem (
					    problem_id, org_id, code_analysis_id, problem_scope, problem_no,
					    title, source_snippet_key, code_language, source_path,
					    source_line_start, source_line_end, code_snippet_hash,
					    generation_status, extractor_version, problem_type, priority,
					    question_focus_item_id, teaches_id)
					VALUES (?, ?, ?, 'TEAM_SHARED_PROBLEM', ?, ?, ?, ?, ?, ?, ?, ?, 'GENERATED', ?, ?, ?, ?, ?)
					""",
					problemId, job.getOrgId(), analysisId, problem.problemNo(),
					problem.title(), problem.snippetKey(), problem.codeLanguage(), problem.sourcePath(),
					problem.lineStart(), problem.lineEnd(), problem.contentHash(),
					problem.extractorVersion(), problem.problemType(), problem.priority(),
					parseUuidOrNull(problem.questionFocusItemId()), parseUuidOrNull(problem.teachId()));

			insertReferences(problemId, job.getOrgId(), problem);
		}

		insertNotGeneratedSlots(analysisId, job, result, usedNumbers);
	}

	/**
	 * 문제의 PK. <b>AI가 준 {@code problemId}를 그대로 쓴다.</b>
	 *
	 * <p>세션 API({@code POST /sessions/{id}/answers})가 응답의 {@code Question.problemId}에
	 * AI의 값을 실어 준다. 우리가 별도 UUID를 만들면 채점 결과가 <b>어느 문제의 것인지 대조할
	 * 방법이 없다</b> — {@code (problemNo, snippetKey)}로 역추적하는 우회는 재분석에서 번호가 바뀌면
	 * 깨진다.
	 *
	 * <p><b>PK 충돌 걱정이 없는 이유.</b> 성공한 분석은 다시 돌지 않는다 —
	 * {@code AnalysisDispatchRepository.BLOCKING_JOB_EXISTS}가 SUCCEEDED·PARTIAL job이 있는 제출을
	 * 디스패치 대상에서 뺀다. 재시도는 <b>실패한</b> 분석에만 일어나고, 실패한 분석은 문제를 만들지
	 * 않았으므로 같은 id가 이미 있을 수 없다. 그래도 충돌하면 AI가 서로 다른 제출에 같은 id를 준
	 * 것이고, 그건 조용히 넘길 계약 위반이 아니라 PK 위반으로 드러나야 한다.
	 *
	 * <p>AI가 값을 주지 않거나 UUID가 아니면 새로 만든다. 그 경우 세션 API와의 대조는 포기하지만,
	 * 문제 자체를 저장하지 못하는 것보다는 낫다.
	 */
	private static UUID problemIdOf(Problem problem) {
		UUID external = parseUuidOrNull(problem.problemId());
		if (external != null) {
			return external;
		}
		log.warn("AI 가 UUID 형태의 problemId 를 주지 않아 새로 만든다. 세션 응답과 대조할 수 없다: problemNo={}",
				problem.problemNo());
		return UUID.randomUUID();
	}

	private void insertReferences(UUID problemId, UUID orgId, Problem problem) {
		if (problem.references() == null) {
			return;
		}
		for (ProblemReference reference : problem.references()) {
			jdbc.update("""
					INSERT INTO assessment_problem_reference (
					    problem_id, org_id, reference_type, display_order,
					    source_path, source_line_start, source_line_end, axis_code, teaches_id, evidence_hash)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""",
					problemId, orgId, reference.referenceType(), reference.displayOrder(),
					reference.path(), reference.lineStart(), reference.lineEnd(),
					reference.axisCode(), parseUuidOrNull(reference.teachId()), reference.evidenceHash());
		}
	}

	/**
	 * 문항을 못 만든 개념을 빈 슬롯으로 남긴다.
	 *
	 * <p>{@code ck_assessment_problem_generation_status_2}가 NOT_GENERATED 분기에서 상세 컬럼을 전부
	 * NULL로 요구하므로 {@code teaches_id}와 사유만 채운다({@code teaches_id}는 그 묶음에 없다).
	 *
	 * <p>번호는 이미 쓰인 것을 피해 1~3에서 고른다. {@code uq_assessment_problem_team_no}가 같은
	 * 분석 안에서 번호 중복을 막고, {@code ck_assessment_problem_problem_no}가 4 이상을 막는다 —
	 * 빈 슬롯이 남는 번호보다 많으면 그 초과분은 버린다.
	 */
	private void insertNotGeneratedSlots(UUID analysisId, AnalysisJob job, AnalysisResultPayload result,
			List<Integer> usedNumbers) {
		if (result.unmatchedTeaches() == null || result.unmatchedTeaches().isEmpty()) {
			return;
		}
		List<Integer> free = new ArrayList<>();
		for (int no = 1; no <= MAX_PROBLEM_NO; no++) {
			if (!usedNumbers.contains(no)) {
				free.add(no);
			}
		}
		int index = 0;
		for (UnmatchedTeach unmatched : result.unmatchedTeaches()) {
			if (index >= free.size()) {
				log.warn("빈 문제 슬롯보다 미매칭 개념이 많아 일부를 버린다: analysisId={}, teachId={}",
						analysisId, unmatched.teachId());
				break;
			}
			jdbc.update("""
					INSERT INTO assessment_problem (
					    org_id, code_analysis_id, problem_scope, problem_no, generation_status,
					    not_generated_reason_code, not_generated_reason_detail, teaches_id)
					VALUES (?, ?, 'TEAM_SHARED_PROBLEM', ?, 'NOT_GENERATED', ?, ?, ?)
					""",
					job.getOrgId(), analysisId, free.get(index), NO_MATCHING_CODE_EVIDENCE,
					unmatched.reason(), parseUuidOrNull(unmatched.teachId()));
			index++;
		}
	}

	/**
	 * 커밋 이력을 귀속과 함께 저장한다.
	 *
	 * <p>{@code repository_id}는 GitHub 제출에만 있다(v08에서 NULL 허용). ZIP 제출은 NULL이고,
	 * 그 경우 {@code uq_commit_attribution_analysis_id_repository_id_commit_hash}가
	 * {@code NULLS NOT DISTINCT}라 중복 커밋은 여전히 막힌다.
	 *
	 * <p>{@code parentSha}는 {@code parent_commit_hash}에 그대로 들어간다(2026-08-09 확정, NULL 허용).
	 * 다만 40자리 0 sentinel이 오면 NULL로 바꾼다 — S-18이 "root 커밋/부모 불명은 sentinel 문자열
	 * 대신 NULL"로 정했고, 실제 응답(08-07)에 그 sentinel이 있었다. 가짜 부모 해시가 남으면 커밋
	 * 그래프를 되짚을 때 존재하지 않는 커밋을 가리키게 된다.
	 */
	private void insertCommitAttributions(UUID analysisId, AnalysisJob job, AnalysisResultPayload result) {
		if (result.gitHistory() == null || result.gitHistory().isEmpty()) {
			return;
		}
		UUID repositoryId = jdbc.queryForList(
						"SELECT repository_id FROM submission WHERE submission_id = ?",
						UUID.class, job.getSubmissionId())
				.stream().findFirst().orElse(null);

		for (GitCommit commit : result.gitHistory()) {
			Attribution attribution = attribute(job.getOrgId(), commit.authorEmail());
			jdbc.update("""
					INSERT INTO commit_attribution (
					    analysis_id, repository_id, attributed_user_id, branch_name, commit_hash,
					    parent_commit_hash, commit_message, author_name, commit_email,
					    authored_at, committed_at, is_merge_commit, is_revert_commit, is_bot_commit,
					    addition_count, deletion_count, changed_line_count, changed_file_count,
					    attribution_status, attribution_method, verification_status)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					ON CONFLICT DO NOTHING
					""",
					analysisId, repositoryId, attribution.userId(),
					commit.branchName() == null ? "unknown" : commit.branchName(),
					commit.commitHash(), normalizeParentSha(commit.parentSha()), commit.commitMessage(),
					commit.authorName() == null ? "unknown" : commit.authorName(),
					commit.authorEmail() == null ? "unknown@example.invalid" : commit.authorEmail(),
					toTimestamp(commit.authoredAt() == null ? commit.committedAt() : commit.authoredAt()),
					toTimestamp(commit.committedAt()),
					Boolean.TRUE.equals(commit.isMergeCommit()),
					Boolean.TRUE.equals(commit.isRevertCommit()),
					Boolean.TRUE.equals(commit.isBotCommit()),
					zeroIfNull(commit.additions()), zeroIfNull(commit.deletions()),
					zeroIfNull(commit.changedLineCount()),
					commit.changedFiles() == null ? 0 : commit.changedFiles().size(),
					attribution.status(), ATTRIBUTION_METHOD_EMAIL, attribution.verification());
		}
	}

	/**
	 * 커밋 이메일로 교육생을 찾는다.
	 *
	 * <p>GitHub의 {@code ...@users.noreply.github.com} 주소를 쓰는 커밋은 매칭되지 않는다. 그래서
	 * UNMATCHED가 정상 결과의 일부다 — 못 찾았다고 적재를 멈추면 커밋 이력이 통째로 사라진다.
	 */
	private Attribution attribute(UUID orgId, String commitEmail) {
		if (commitEmail == null || commitEmail.isBlank()) {
			return new Attribution(null, "UNMATCHED", "UNVERIFIED");
		}
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT user_id, commit_email_status FROM app_user
				 WHERE org_id = ? AND commit_email_normalized = lower(?)
				 LIMIT 1
				""", orgId, commitEmail.trim());
		if (rows.isEmpty()) {
			return new Attribution(null, "UNMATCHED", "UNVERIFIED");
		}
		Object status = rows.get(0).get("commit_email_status");
		return new Attribution(
				(UUID) rows.get(0).get("user_id"),
				"MATCHED",
				"VERIFIED".equals(status) ? "VERIFIED" : "PENDING");
	}

	private record Attribution(UUID userId, String status, String verification) {
	}

	/**
	 * 요구사항 P/F 판정을 새 버전으로 남긴다.
	 *
	 * <p>덮어쓰지 않고 버전을 올리는 이유: {@code assessment_version}과
	 * {@code supersedes_assessment_id}가 있는 이력 구조다. 재분석에서 판정이 뒤집히면 "언제 무엇으로
	 * 바뀌었나"가 남아야 한다.
	 */
	private void upsertRequirementAssessments(UUID analysisId, AnalysisJob job, AnalysisResultPayload result) {
		if (result.requirementResults() == null) {
			return;
		}
		for (RequirementResult requirement : result.requirementResults()) {
			UUID requirementId = parseUuidOrNull(requirement.requirementId());
			if (requirementId == null) {
				log.warn("requirementId 가 UUID 가 아니라 판정을 건너뛴다: value={}", requirement.requirementId());
				continue;
			}
			Map<String, Object> previous = jdbc.queryForList("""
					SELECT assessment_id, assessment_version FROM project_requirement_assessment
					 WHERE requirement_id = ? AND assessment_round_id = ? AND team_id = ?
					 ORDER BY assessment_version DESC LIMIT 1
					""", requirementId, job.getAssessmentRoundId(), job.getTeamId())
					.stream().findFirst().orElse(null);

			jdbc.update("""
					INSERT INTO project_requirement_assessment (
					    requirement_id, assessment_round_id, team_id, org_id, result,
					    evidence_summary, source_submission_id, analysis_id,
					    assessment_version, supersedes_assessment_id, assessed_at, assessment_note)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""",
					requirementId, job.getAssessmentRoundId(), job.getTeamId(), job.getOrgId(),
					normalizeVerdict(requirement.verdict()), requirement.evidence(),
					job.getSubmissionId(), analysisId,
					previous == null ? 1 : ((Number) previous.get("assessment_version")).intValue() + 1,
					previous == null ? null : previous.get("assessment_id"),
					Timestamp.from(Instant.now()), requirement.note());
		}
	}

	/** {@code ck_project_requirement_assessment_result}: PENDING·PASS·FAIL 셋뿐이다. */
	private static String normalizeVerdict(String verdict) {
		if ("PASS".equals(verdict) || "FAIL".equals(verdict)) {
			return verdict;
		}
		return "PENDING";
	}

	private static List<Problem> problemsOf(AnalysisResultPayload result) {
		return result.problems() == null ? List.of() : result.problems();
	}

	/**
	 * root 커밋의 40자리 0 sentinel을 NULL로 바꾼다.
	 *
	 * <p>{@code parent_commit_hash}는 NULL 허용이고(S-18), "부모 없음"의 정식 표현이 NULL이다.
	 * sentinel을 그대로 두면 존재하지 않는 커밋을 가리키는 값이 원장에 남는다.
	 */
	private static String normalizeParentSha(String parentSha) {
		if (parentSha == null || parentSha.isBlank() || parentSha.chars().allMatch(ch -> ch == '0')) {
			return null;
		}
		return parentSha;
	}

	private static int zeroIfNull(Integer value) {
		return value == null ? 0 : value;
	}

	private static Timestamp toTimestamp(Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}

	/**
	 * AI가 돌려주는 식별자 중 일부는 우리가 요청에 실어 보낸 값의 에코라 UUID지만, 테스트 픽스처처럼
	 * 아닌 값이 올 수 있다. 그때 {@code IllegalArgumentException}으로 적재 전체를 죽이는 대신 비운다 —
	 * 해당 컬럼은 전부 NULL 허용이고, 문제·근거 자체는 저장하는 편이 낫다.
	 */
	private static UUID parseUuidOrNull(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		return Optional.of(raw.trim()).filter(value -> {
			try {
				UUID.fromString(value);
				return true;
			} catch (IllegalArgumentException exception) {
				log.warn("UUID 가 아닌 식별자를 비우고 진행한다: value={}", value);
				return false;
			}
		}).map(UUID::fromString).orElse(null);
	}

	/**
	 * 2026-08-10 정정: 이전에는 Jackson 2 {@code ObjectMapper}를 썼는데, {@code jsr310} 모듈이 없어
	 * {@code java.time.Instant}가 하나라도 섞이면(예: {@code gitHistory[].committedAt}) 예외를 던졌다.
	 * {@code record()}가 이 실패를 삼키지 않으므로 {@code assessment_problem}·{@code problem_stage}가
	 * 통째로 저장되지 않고 job만 SUCCEEDED로 남는 사고로 이어졌다. Jackson 3(현재 이 프로젝트의
	 * 표준)은 java.time을 기본 지원해 별도 모듈이 필요 없다.
	 */
	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (RuntimeException exception) {
			throw new IllegalStateException("분석 결과를 JSON 으로 만들지 못했다.", exception);
		}
	}
}
