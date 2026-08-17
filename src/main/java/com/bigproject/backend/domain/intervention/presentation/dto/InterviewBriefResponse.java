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

		@Schema(description = """
				판정 근거 문구이며 그대로 표시한다.
				**대괄호 태그(`[SEVERE]` 등)는 붙지 않는다**(30차 R8).
				""", example = "평균 도달 단계 2.33 → 1.67. 2단 미만 2개.")
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

		// 30차 R2③ — briefState가 FAILED면 생성된 것이 없어 null이다. 29차 R2로 priorInterview·
		// savedRecord·voidEvidence에 nullable을 붙일 때 이 필드만 빠졌다. 표기가 없으면 화면이
		// FAILED 분기를 컴파일러에게 검사받지 못하고, string 타입인데 런타임에 undefined가 된다.
		@Schema(description = """
				**★ AI가 생성한 여는 말.** 화면 ①칸에 그대로 표시한다.
				1~3문장 구어체이고 점수·단계·위험 유형을 직접 언급하지 않는다.

				⚠️ **`briefState`가 `FAILED`·`NONE`이면 `null`이다** — 생성에 실패했거나 아직
				만들지 않은 브리프다. 그때는 `items`도 빈 배열이므로 `briefState`로 먼저 갈라
				"브리프를 만들지 못했습니다"를 그리면 된다.
				""", example = "지난 회차엔 3개 중 1개만 막혔었는데, 이번엔 2개나 막혔더라고요. 편하게 무슨 일이 있었는지 들어보고 싶어서 불렀어요.",
				nullable = true)
		String openingRemark,

		@Schema(description = """
				**★ AI가 생성한 질문 체크리스트.** 4~8개(첫 면담이면 6~8개)입니다.

				⚠️ **전 항목을 그대로 그립니다.** 화면에는 질문을 고르는 UI가 없습니다
				(정의서 §7 — 고르게 하면 "뭘 물을지" 부담이 생기고 그게 이 화면이 없애려던 것).
				""")
		List<BriefItemResponse> items,

		// 29차 R2 ② — 설명만 "없으면 null"이라 적고 타입이 null을 안 받고 있었다. nullable을 붙이면
		// springdoc이 oneOf: [$ref, null]로 내보내 화면이 null 검사를 강제받는다(22차 Team.submission과 같다).
		@Schema(description = "지난 면담에서 정한 것. 없으면 null — ①칸의 ⚠ 줄이 통째로 빠진다",
				nullable = true)
		PriorInterviewResponse priorInterview,

		@Schema(description = "저장된 매니저 입력. 처음 여는 브리프는 null", nullable = true)
		SavedRecordResponse savedRecord,

		@Schema(description = "⚠️ **미구현** — 교안 위치·반 문제 판정이 DB 회신 대기입니다. 현재 빈 배열")
		List<ConceptResponse> concepts,

		@Schema(description = """
				"시스템이 본 것" — **무효 응시 브리프에서만** 채워집니다(그 외에는 null).

				일반 브리프에 띄우지 않는 이유는 면담이 **추궁**이 되기 때문입니다 —
				이 화면이 하려는 일은 "다음 한 주를 어디에 쓸지"를 정하는 것입니다(정의서 §6-2).

				⚠️ **관찰이지 판정이 아닙니다.** 화면도 "판단은 하지 않습니다"로 감싸 보여줍니다.
				""", nullable = true)
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

			@Schema(description = """
					**매니저만 보는 근거.** 어떤 데이터에서 나온 질문인지.

					**내부 식별자·코드는 실리지 않습니다**(32차 R6). 서버가 내보내기 전에
					`interviewSourceId` 같은 값을 걷어내고, 위험 사유 코드와 축 코드를 화면이 쓰는
					말로 바꿉니다 — `PERSISTENT_LOW` → `지속 저점`, `L3` → `대안 비교`,
					`문제 1` → `1번 문항`.

					그대로 그리면 됩니다.
					""",
					example = "1번 문항 대안 비교 인터뷰 기반 Q&A 질문")
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

			@Schema(description = """
					상세 사유(매니저가 타이핑). **안 썼으면 `null`입니다.**

					🔴 **32차 R8 — 더 이상 `(기록 없음)`으로 치환하지 않습니다.** 종전에는 빈 값을
					그 문구로 바꿔 저장해서, 다시 열면 입력칸에 그 글자가 들어 있었고 그대로 저장하면
					진짜 타이핑한 서술로 남았습니다. 지금은 안 쓴 것과 쓴 것이 구분됩니다.

					⚠️ 이 회신 전에 저장된 브리프에는 그 문구가 그대로 남아 있습니다 — 일괄 정리가
					필요하면 말씀해 주세요.
					""", nullable = true)
			String why,

			@Schema(description = "추후 계획(매니저가 타이핑). 안 썼으면 `null`입니다", nullable = true)
			String nextAction) {
	}

	@Schema(description = "막힌 개념 — 미구현")
	public record ConceptResponse(String name, String curriculumRef, String groupIssueClassLabel) {
	}

	@Schema(description = "무효 응시 근거. 화면 문구: `3문항 중 2문항 무응답 · 나머지 1문항은 질문 문장을 그대로 복사 · 총 응답 시간 4분`")
	public record VoidEvidenceResponse(

			@Schema(description = "**문제 단위** 무응답 수. 그 문제의 단계가 전부 답 없이 끝난 경우", example = "2")
			int unanswered,

			@Schema(description = "그 회차 문제 수(최대 3)", example = "3")
			int totalQuestions,

			@Schema(description = "답변이 질문 문장과 같은 단계가 있는가. **정규화 후 완전 일치**로 판정한다", example = "true")
			boolean copied,

			@Schema(description = "세션 시작~종료 분", example = "4")
			int durationMin) {
	}
}
