package com.bigproject.backend.domain.intervention.application;

import com.bigproject.backend.domain.intervention.application.dto.InterviewBriefAiRequest;
import com.bigproject.backend.domain.intervention.application.dto.InterviewBriefAiResponse;
import com.bigproject.backend.domain.intervention.domain.InterviewBriefContextRepository;
import com.bigproject.backend.domain.intervention.domain.InterviewBriefWriteRepository;
import com.bigproject.backend.domain.intervention.domain.InterviewCaseLookupRepository.CaseSummary;
import com.bigproject.backend.domain.intervention.domain.InterviewSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 브리프 생성의 <b>쓰기 두 구간</b>. AI 호출을 사이에 두고 트랜잭션이 갈린다.
 *
 * <pre>
 * TX1  interview + interview_source[] + interview_brief(DRAFT) + 후보 전이  → 커밋
 *        ↓  AI 호출 (트랜잭션 밖, 최악 120초)
 * TX2  opening_remark + brief_item + history                              → 커밋
 * </pre>
 *
 * <h2>왜 별도 빈인가</h2>
 *
 * <p>같은 클래스 안에서 {@code @Transactional} 메서드를 자기 호출하면 <b>프록시를 타지 않아
 * 트랜잭션이 안 걸린다.</b> 서비스가 이 빈을 주입받아 부르면 프록시를 거친다.
 *
 * <h2>왜 AI 호출을 트랜잭션 밖에 두는가</h2>
 *
 * <p>수십 초짜리 외부 HTTP다. 트랜잭션 안에서 부르면 그동안 DB 커넥션까지 붙잡아
 * 커넥션 풀이 먼저 마른다.
 */
@Component
@RequiredArgsConstructor
public class InterviewBriefWriter {

	private final InterviewBriefWriteRepository writeRepository;
	private final InterviewSourceRepository sourceRepository;
	private final InterviewBriefContextRepository contextRepository;

	/**
	 * TX1 — 행을 먼저 만들고 AI 요청까지 조립한다.
	 *
	 * <p>브리프 행을 AI 호출 <b>전에</b> 만드는 이유는 {@code last_request_id}/
	 * {@code fingerprint}가 중복 호출 방지 장치라, 호출 후에 만들면 대조할 대상이 없어서다.
	 */
	@Transactional
	public BriefDraft prepare(CaseSummary summary, UUID managerUserId) {
		UUID interviewId = summary.interviewId() != null
				? summary.interviewId()
				: createInterview(summary.candidateId(), managerUserId);

		boolean firstInterview = !writeRepository.hasPriorCompletedInterview(summary.traineeUserId());
		// 무효 확인이 끝나야 briefType이 정해진다. CONFIRMED_INVALID면 여는 말과 질문이
		// 통째로 다른 브리프가 나온다(정의서 §6-2).
		String briefType = "INVALID".equals(summary.riskType()) ? "INVALID_ATTEMPT" : "STANDARD";

		int versionNo = writeRepository.nextVersionNo(interviewId);
		UUID briefId = writeRepository.insertBriefDraft(
				interviewId, summary.candidateId(), briefType, versionNo, firstInterview, managerUserId);

		Set<UUID> sourceIds = new HashSet<>();
		InterviewBriefAiRequest request = assemble(
				summary, managerUserId, interviewId, briefId, briefType, firstInterview, sourceIds);

		return new BriefDraft(interviewId, briefId, versionNo, request, sourceIds);
	}

	/**
	 * TX1(재생성) — 기존 브리프를 밀어내고 새 버전을 준비한다.
	 *
	 * <p>{@code version_no}가 올라가므로 멱등키({@code briefId:versionNo})도 자동으로 달라진다 —
	 * 이전 키를 재사용하면 {@code ai_usage.idempotency_key} 전역 UNIQUE에 걸리고 AI는 409를 준다.
	 */
	@Transactional
	public BriefDraft prepareRegeneration(CaseSummary summary, UUID managerUserId) {
		if (summary.interviewId() == null) {
			// 만든 적이 없으면 재생성이 아니라 생성이다. 호출부가 막지만 방어로 둔다.
			return prepare(summary, managerUserId);
		}
		writeRepository.supersedeExistingDrafts(summary.interviewId());
		return prepare(summary, managerUserId);
	}

	private UUID createInterview(UUID candidateId, UUID managerUserId) {
		UUID interviewId = writeRepository.insertInterview(candidateId, managerUserId);
		// 면담 생성 성공 시에만 전이하고 원자적으로 연결한다(테이블 COMMENT).
		writeRepository.markInterviewCreated(candidateId);
		return interviewId;
	}

	/**
	 * 근거 행을 만들면서 AI 요청을 조립한다.
	 *
	 * <p>{@code sourceIds}에 만든 id를 전부 모은다 — AI 응답 검증의 대조군이다.
	 */
	private InterviewBriefAiRequest assemble(CaseSummary summary, UUID managerUserId, UUID interviewId,
			UUID briefId, String briefType, boolean firstInterview, Set<UUID> sourceIds) {

		List<InterviewBriefAiRequest.RiskReason> reasons = new ArrayList<>();
		for (InterviewBriefContextRepository.ReasonRow row : contextRepository.findRiskReasons(summary.candidateId())) {
			UUID sourceId = sourceRepository.createFromCandidateReason(
					interviewId, row.candidateReasonId(), managerUserId);
			sourceIds.add(sourceId);
			reasons.add(new InterviewBriefAiRequest.RiskReason(
					row.reasonCode(), row.evaluationStatus(), row.notApplicableReasonCode(),
					row.reasonSummary(), row.detectedAt(), row.sourceProblemStageId(), sourceId));
		}

		var attempt = contextRepository.findAttempt(summary.candidateId()).orElse(null);
		UUID attemptSourceId = null;
		UUID sessionSourceId = null;
		if (attempt != null) {
			attemptSourceId = sourceRepository.createFromAttempt(interviewId, attempt.attemptId(), managerUserId);
			sourceIds.add(attemptSourceId);
			if (attempt.sessionId() != null) {
				sessionSourceId = sourceRepository.createFromSession(
						interviewId, attempt.sessionId(), managerUserId);
				sourceIds.add(sessionSourceId);
			}
		}

		List<InterviewBriefContextRepository.StageRow> allStages =
				contextRepository.findStages(summary.candidateId());

		List<InterviewBriefAiRequest.ProblemComprehension> problems = new ArrayList<>();
		for (InterviewBriefContextRepository.ProblemRow problem : contextRepository.findProblems(summary.candidateId())) {
			UUID problemSourceId = sourceRepository.createFromProblem(
					interviewId, problem.problemId(), managerUserId);
			sourceIds.add(problemSourceId);

			List<InterviewBriefAiRequest.ComprehensionStage> stages = new ArrayList<>();
			for (InterviewBriefContextRepository.StageRow stage : allStages) {
				if (!stage.problemId().equals(problem.problemId())) {
					continue;
				}
				UUID stageSourceId = sourceRepository.createFromProblemStage(
						interviewId, stage.problemStageId(), managerUserId);
				sourceIds.add(stageSourceId);
				stages.add(toStage(stage, stageSourceId));
			}

			problems.add(new InterviewBriefAiRequest.ProblemComprehension(
					problem.problemNo(), problem.conceptName(), problem.conceptNameSource(),
					problem.problemScope(), problem.generationStatus(), problem.notGeneratedReasonCode(),
					problem.bestSuccessStage(), toCodeContext(problem), problemSourceId, stages));
		}

		List<InterviewBriefAiRequest.ObservationNote> notes = new ArrayList<>();
		for (var note : contextRepository.findObservationNotes(summary.candidateId(), managerUserId)) {
			UUID noteSourceId = sourceRepository.createFromObservationNote(
					interviewId, note.noteId(), managerUserId);
			sourceIds.add(noteSourceId);
			notes.add(new InterviewBriefAiRequest.ObservationNote(
					note.occurredAt(), note.content(), noteSourceId, note.visibility()));
		}

		List<InterviewBriefAiRequest.PriorInterview> priors = contextRepository
				.findPriorInterviews(summary.traineeUserId(), interviewId).stream()
				.map(row -> new InterviewBriefAiRequest.PriorInterview(
						row.completedAt(), row.resultSummary(), row.activities(), row.askedQuestions()))
				.toList();

		return new InterviewBriefAiRequest(
				briefId,
				contextRepository.findTarget(summary.candidateId()).orElse(null),
				new InterviewBriefAiRequest.BriefContext(briefType, firstInterview),
				reasons,
				contextRepository.findValidityReview(summary.candidateId()).orElse(null),
				new InterviewBriefAiRequest.Comprehension(
						attempt == null ? null : attempt.attemptType(),
						attempt == null ? null : attempt.attemptStatus(),
						attempt == null ? null : attempt.terminalReasonCode(),
						attempt == null ? null : attempt.sessionEndReasonCode(),
						attemptSourceId, sessionSourceId, problems),
				priors,
				notes);
	}

	/** {@code NOT_GENERATED} 문제는 좌표 6개가 CHECK로 전부 NULL이라 객체 자체를 생략한다. */
	private static InterviewBriefAiRequest.CodeContext toCodeContext(
			InterviewBriefContextRepository.ProblemRow problem) {
		if (problem.sourcePath() == null || problem.sourceLineStart() == null) {
			return null;
		}
		return new InterviewBriefAiRequest.CodeContext(
				problem.codeLanguage(), problem.sourcePath(),
				problem.sourceLineStart(), problem.sourceLineEnd(),
				problem.sourceSnippetKey(), problem.codeSnippetHash());
	}

	private static InterviewBriefAiRequest.ComprehensionStage toStage(
			InterviewBriefContextRepository.StageRow row, UUID sourceId) {
		return new InterviewBriefAiRequest.ComprehensionStage(
				row.problemStageId(), row.axisCode(), row.status(),
				row.questionText(), row.questionAnswerText(), row.questionScore(), row.questionPassed(),
				row.firstHintText(), row.firstHintAnswerText(), row.firstHintScore(), row.firstHintPassed(),
				row.secondHintText(), row.secondHintAnswerText(), row.secondHintScore(), row.secondHintPassed(),
				row.flagged(), sourceId);
	}

	/**
	 * TX2 — 생성 결과 저장.
	 *
	 * <p>여기까지 오면 {@code interviewSourceId} 검증은 이미 끝났다(서비스가 한다).
	 */
	@Transactional
	public void saveResult(UUID briefId, InterviewBriefAiResponse response,
			UUID requestId, String fingerprint, UUID actorUserId) {
		writeRepository.saveGeneratedBrief(briefId, response.openingRemark(), requestId, fingerprint);
		writeRepository.insertBriefItems(briefId, response.items());
		writeRepository.insertItemHistory(briefId, actorUserId, requestId);
	}

	/**
	 * @param sourceIds AI가 되돌려줄 {@code interviewSourceId}의 허용 집합
	 */
	public record BriefDraft(
			UUID interviewId,
			UUID briefId,
			int versionNo,
			InterviewBriefAiRequest request,
			Set<UUID> sourceIds) {
	}
}
