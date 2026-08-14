package com.bigproject.backend.domain.intervention.presentation.dto;

import com.bigproject.backend.domain.intervention.application.InterviewBriefService.BriefView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** MG-04 면담 브리프 응답. */
@Schema(description = "면담 브리프")
public record InterviewBriefResponse(

		UUID caseId,
		UUID traineeId,

		@Schema(example = "김민준")
		String name,

		@Schema(example = "A반")
		String className,

		@Schema(description = "위험 유형. 목록과 같은 값이다", example = "DECLINE")
		String riskType,

		@Schema(description = "판정 근거 문구", example = "2단 이하 1 → 2")
		String riskSummary,

		@Schema(description = """
				무효 응시 브리프인가. true면 **여는 말과 질문이 통째로 다르다**(정의서 §6-2) —
				`3개 중 0개`가 아니라 응시 자체를 안 한 것이라 기본 브리프 문장을 쓰면 말이 안 된다.
				""")
		boolean isVoid,

		@Schema(description = "이 교육생의 첫 면담인가. AI가 라포 형성용 도입 질문을 넣는 기준이다")
		boolean firstInterview,

		@Schema(description = "브리프 상태 `NONE` / `FAILED` / `DRAFT` / `CONFIRMED`", example = "DRAFT")
		String briefState,

		@Schema(description = """
				**★ AI가 생성한 여는 말.** 화면 ①칸에 그대로 표시한다.
				1~3문장 구어체이고 점수·단계·위험 유형을 직접 언급하지 않는다.
				""", example = "지난 회차엔 3개 중 1개만 막혔었는데, 이번엔 2개나 막혔더라고요. 편하게 무슨 일이 있었는지 들어보고 싶어서 불렀어요.")
		String openingRemark,

		@Schema(description = """
				**★ AI가 생성한 질문 체크리스트.** 4~8개(첫 면담이면 6~8개)입니다.

				⚠️ **전 항목을 그대로 그립니다.** 화면에는 질문을 고르는 UI가 없습니다
				(정의서 §7 — 고르게 하면 "뭘 물을지" 부담이 생기고 그게 이 화면이 없애려던 것).
				""")
		List<BriefItemResponse> items,

		@Schema(description = "지난 면담에서 정한 것. 없으면 null — ①칸의 ⚠ 줄이 통째로 빠진다")
		PriorInterviewResponse priorInterview,

		@Schema(description = "저장된 매니저 입력. 처음 여는 브리프는 null")
		SavedRecordResponse savedRecord,

		@Schema(description = "⚠️ **미구현** — 교안 위치·반 문제 판정이 DB 회신 대기입니다. 현재 빈 배열")
		List<ConceptResponse> concepts,

		@Schema(description = "⚠️ **미구현** — \"질문 문장 그대로 복사\" 판정 규칙이 DB 회신 대기입니다. 현재 null")
		VoidEvidenceResponse voidEvidence) {

	public static InterviewBriefResponse from(BriefView view) {
		return new InterviewBriefResponse(
				view.caseId(),
				view.traineeId(),
				view.name(),
				view.className(),
				view.riskType(),
				view.riskSummary(),
				view.isVoid(),
				view.firstInterview(),
				view.briefState(),
				view.openingRemark(),
				view.items().stream()
						.map(item -> new BriefItemResponse(
								item.itemId(), item.questionText(), item.questionRationale(), item.suggestedOrder()))
						.toList(),
				view.priorInterview() == null ? null
						: new PriorInterviewResponse(
								view.priorInterview().completedAt(), view.priorInterview().nextAction()),
				view.savedRecord() == null ? null
						: new SavedRecordResponse(
								view.savedRecord().causes(),
								view.savedRecord().why(),
								view.savedRecord().nextAction()),
				view.concepts().stream()
						.map(concept -> new ConceptResponse(
								concept.name(), concept.curriculumRef(), concept.groupIssueClassLabel()))
						.toList(),
				view.voidEvidence() == null ? null
						: new VoidEvidenceResponse(
								view.voidEvidence().unanswered(),
								view.voidEvidence().totalQuestions(),
								view.voidEvidence().copied(),
								view.voidEvidence().durationMin()));
	}

	@Schema(description = "질문 항목")
	public record BriefItemResponse(
			UUID itemId,

			@Schema(description = "매니저가 그대로 읽는 구어체 질문", example = "이번에 어떤 역할을 맡았어요?")
			String questionText,

			@Schema(description = "**매니저만 보는 근거.** 어떤 데이터에서 나온 질문인지",
					example = "Deployment 롤링 업데이트 관련 확인")
			String questionRationale,

			@Schema(description = "제안 순서. 1부터 중복 없는 연속 정수", example = "2")
			Integer suggestedOrder) {
	}

	@Schema(description = "지난 면담 기록")
	public record PriorInterviewResponse(Instant completedAt, String nextAction) {
	}

	@Schema(description = "저장된 매니저 입력")
	public record SavedRecordResponse(

			@Schema(description = """
					고른 원인 분류. `CONCEPT_GAP` / `OUT_OF_SCOPE` / `TIME_SHORTAGE` / `EXPRESSION`
					/ `DIFFICULTY_UP` / `TEAM_DEPENDENCE` / `CONDITION`
					""", example = "[\"CONCEPT_GAP\"]")
			List<String> causes,

			@Schema(description = "상세 사유(매니저가 타이핑)")
			String why,

			@Schema(description = "추후 계획(매니저가 타이핑)")
			String nextAction) {
	}

	@Schema(description = "막힌 개념 — 미구현")
	public record ConceptResponse(String name, String curriculumRef, String groupIssueClassLabel) {
	}

	@Schema(description = "무효 응시 근거 — 미구현")
	public record VoidEvidenceResponse(int unanswered, int totalQuestions, boolean copied, int durationMin) {
	}
}
