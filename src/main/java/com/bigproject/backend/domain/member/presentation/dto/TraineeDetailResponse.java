package com.bigproject.backend.domain.member.presentation.dto;

import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.InactivationReasonCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "매니저 교육생 상세(MG-06)의 헤더와 회차별 도달 단계 격자")
public record TraineeDetailResponse(
		@Schema(description = "교육생 사용자 ID") UUID traineeId,
		@Schema(description = "이름") String name,
		@Schema(description = "이메일") String email,
		@Schema(description = "소속 기수 ID") UUID cohortId,
		@Schema(description = "소속 기수 이름. 헤더의 `7기 · C반`에서 앞부분입니다", example = "7기")
		String cohortName,
		@Schema(description = "계정 상태. `INVITED`(초대 대기) · `ACTIVE`(활성) · `INACTIVE`(비활성)")
		AccountStatus status,
		@Schema(description = "현재 소속 반 ID. 배정이 없으면 null", nullable = true) UUID classroomId,
		@Schema(description = "현재 소속 반 이름. 배정이 없으면 null", nullable = true) String className,

		@Schema(description = "비활성화 사유 코드. 활성이면 null", nullable = true)
		InactivationReasonCode inactivatedReasonCode,
		@Schema(description = "비활성화 상세 사유. **INACTIVE여도 null일 수 있습니다**", nullable = true)
		String inactivatedReason,
		@Schema(description = "비활성화 시각. 활성이면 null", nullable = true)
		OffsetDateTime inactivatedAt,

		@Schema(description = """
				헤더 위험 배지에 넣을 **단일** 코드입니다. **가장 최근에 응시한 회차 하나**의
				`primaryStatusCode`이며, 응시한 회차가 없으면 null입니다.

				회차 격자는 회차마다 배지를 따로 그리지만 헤더는 사람 한 명을 말하므로 최신 판정만
				싣습니다 — 지난 회차의 위험까지 헤더에 얹으면 이미 해소된 것이 계속 남습니다.
				""", example = "STAGE_DECLINE", nullable = true)
		String riskTypeCode,
		@Schema(description = """
				위험 배지 옆 판정식이며 `2단 이하 0 → 2`가 이 값입니다.
				원장은 `interview_candidate_reason.reason_summary`이고 `riskTypeCode`와 **같은 회차**에서
				읽습니다. 위험이 없으면 null입니다.
				""", example = "2단 이하 0 → 2", nullable = true)
		String riskReasonSummary,

		@Schema(description = "우수로 발견된 누적 횟수. 근거가 없으면 0") int excellentOccurrenceCount,
		@Schema(description = "우수로 발견된 프로젝트 차수 전부. 최신 차수부터 내림차순", example = "[3, 2, 1]")
		int[] excellentAssessmentSequenceNos,

		@Schema(description = """
				회차별 도달 단계 격자입니다. **차수 오름차순**이라 화면이 `미프 1차 · 2차 · 3차`를
				위에서 아래로 그리는 순서와 같습니다. 기수가 아직 회차를 열지 않았으면 빈 배열입니다.
				""")
		List<Round> rounds
) {

	@Schema(name = "TraineeDetailRound", description = "회차 한 줄")
	public record Round(
			@Schema(description = "평가 회차 ID") UUID assessmentRoundId,
			@Schema(description = "**기수 안의 회차 순번.** 화면의 `미프 3차`에서 3이 이 값입니다", example = "3")
			int cohortRoundNo,
			@Schema(description = "프로젝트 안의 회차 번호. 미니프로젝트는 **늘 1**이라 차수 표기에 쓸 수 없습니다", example = "1")
			int roundNo,
			@Schema(description = "회차 이름", example = "3차 이해도 확인") String roundName,
			@Schema(description = "회차가 속한 프로젝트 ID") UUID projectId,
			@Schema(description = "회차가 속한 프로젝트 이름", example = "미니프로젝트 3") String projectName,

			@Schema(description = "그 회차의 응시 시도 ID. 아직 응시하지 않았으면 null", nullable = true)
			UUID attemptId,
			@Schema(description = """
					응시 시도 상태입니다. `NOT_STARTED` · `SUBMITTED` · `ANALYZING` · `SESSION_READY` ·
					`SESSION_IN_PROGRESS` · `COMPLETED` · `FAILED` · `EXPIRED`.
					""", example = "COMPLETED")
			String resultStatus,
			@Schema(description = """
					배지 한 칸에 넣을 **단일** 코드이며 정책 문서 §7의 2층 구조를 그대로 담습니다 —
					1층 응시상태(`NOT_ATTENDED` 미응시 → `SESSION_INCOMPLETE` 응시 중단 →
					`INVALID_ATTEMPT` 무효 응시)가 걸리면 2층 위험 유형(`LOW_PARTICIPATION` →
					`CONTRIBUTION_UNDERSTANDING_GAP` → `STAGE_DECLINE` → `PERSISTENT_LOW`)은 보지 않습니다.
					걸린 것이 없으면 null(정상)입니다.
					""", example = "STAGE_DECLINE", nullable = true)
			String primaryStatusCode,
			@Schema(description = """
					이번 회차에 걸린 위험 유형 **전부**입니다. 동시에 여러 개가 걸릴 수 있어
					`primaryStatusCode`와 따로 냅니다. 없으면 빈 배열입니다.
					""", example = "[\"STAGE_DECLINE\"]")
			List<String> matchedRiskTypeCodes,
			@Schema(description = "그 회차 판정식. 위험이 없으면 null", nullable = true)
			String riskReasonSummary,
			@Schema(description = """
					`primaryStatusCode`가 `NOT_ATTENDED`·`SESSION_INCOMPLETE`일 때만 값이 있는 시각이며
					화면의 `세션 중단 · 07-14`가 이 값입니다.
					""", nullable = true)
			OffsetDateTime terminalAt,

			@Schema(description = """
					`2단 이하` 칸의 **분모**이며 그 회차에 이 교육생에게 실제로 만들어진 문항 수입니다.
					코드에 근거가 없어 문항이 생성되지 않은(`NOT_GENERATED`) 개념은 물을 수 없어 빠지므로
					사람마다 다릅니다.
					""", example = "3")
			int expectedConceptCount,
			@Schema(description = """
					도달 단계 **2단 미만**(0~1단)인 문항 수입니다. 답한 문항이 하나도 없으면 null이며
					화면은 그때 `—`를 그립니다 — 전부 통과한 0과 구분해야 합니다.
					""", example = "2", nullable = true)
			Integer lowStageConceptCount,
			@Schema(description = "이번 회차도 우수인지. `excellentAssessmentSequenceNos`에 이 차수가 있으면 true")
			boolean excellent,
			@Schema(description = "회차 당시 팀 ID. 팀 배정 전이면 null", nullable = true) UUID teamId,

			@Schema(description = """
					격자 한 줄의 칸들이며 **문항 번호 오름차순**입니다. 화면의 개념 라벨과 도달 단계가
					이 배열 그대로입니다.
					""")
			List<Concept> concepts
	) {
	}

	@Schema(name = "TraineeDetailConcept", description = "격자 한 칸 — 그 회차에 검증한 개념 하나")
	public record Concept(
			@Schema(description = "문항 ID") UUID problemId,
			@Schema(description = "문항 번호(1~3)", example = "1") int problemNo,
			@Schema(description = "검증 개념 ID. 개인 기여 문항은 개념이 붙지 않아 null", nullable = true)
			UUID conceptId,
			@Schema(description = """
					개념 이름이며 격자 열 라벨(`인증 흐름`·`API 설계`)입니다. 개념이 붙지 않는 개인 기여
					문항은 문제 제목으로 물러섭니다.
					""", example = "HITL Trigger")
			String conceptName,
			@Schema(description = "`GENERATED`(문항 생성됨) · `NOT_GENERATED`(코드에 근거가 없어 못 물었다)",
					example = "GENERATED")
			String generationStatus,
			@Schema(description = """
					도달 단계 0~4단입니다. **null은 0단이 아닙니다** — 문항이 없거나(`NOT_GENERATED`)
					한 축도 답하지 않은 경우이며 화면은 `▨ 문항 없음`/`—`를 그립니다.
					""", example = "3", nullable = true)
			Integer reachLevel
	) {
	}
}
