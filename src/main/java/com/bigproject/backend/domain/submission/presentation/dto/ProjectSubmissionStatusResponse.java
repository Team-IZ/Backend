package com.bigproject.backend.domain.submission.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = """
		프로젝트 회차의 팀 단위 제출·분석과 팀원 개인 응시 현황.

		**행이 2계층이다.** 팀 중 한 명이 내면 팀원 전원이 같은 코드를 쓰므로 제출·분석·요구사항은
		팀에, 응시는 개인에 붙는다. 그래서 같은 팀에서 제출 상태가 갈릴 수 없다.

		요구사항 목록은 팀이 아니라 프로젝트에 달린 값이라 최상위에 한 번만 싣는다. 팀별 판정은
		`teams[].requirementResults[]`이며 아직 분석되지 않은 팀은 빈 배열이다.

		🔴 **범위는 호출한 매니저의 담당 반이다.** 프로젝트 전체가 아니므로 `summary`의 팀 수,
		`unassignedMemberCount`, `teamFormationStage`가 같은 회차라도 매니저마다 다르다.
		""")
public record ProjectSubmissionStatusResponse(
		UUID projectId,
		String projectName,
		UUID assessmentRoundId,
		@Schema(description = "회차 번호이며 프로젝트 안에서만 유일합니다.", example = "1")
		int roundNo,
		String roundName,
		@Schema(description = "제출 마감 시각입니다.")
		Instant submissionDueAt,
		@Schema(description = """
				팀 편성 단계입니다.

				| 값 | 뜻 |
				|---|---|
				| `NOT_STARTED` | 팀이 하나도 없음 |
				| `FORMING` | 미배정 인원이 남아 있음 |
				| `READY_TO_CONFIRM` | 전원 배정됐으나 확정 전 팀이 있음 |
				| `CONFIRMED` | 전 팀 확정됨 |
				| `CLOSED` | 종료된 프로젝트 |
				""", example = "CONFIRMED")
		String teamFormationStage,
		@Schema(description = """
				제출이 열렸는지 여부입니다. `teamFormationStage`가 `CONFIRMED`·`CLOSED`일 때 true입니다.

				**화면은 이 값만 보고 표를 그릴지 빈 상태를 보여줄지 정합니다.** 단계 이름으로 다시
				판정하면 같은 규칙이 서버와 화면 두 곳에 생깁니다.
				""")
		boolean submissionOpened,
		@Schema(description = "종료된 회차입니다. 독촉·팀 이동 등 편성 액션을 잠급니다.")
		boolean locked,
		@Schema(description = "미배정 인원 수입니다. 0이 아니면 제출이 열리지 않습니다.", example = "0")
		long unassignedMemberCount,
		Summary summary,
		@Schema(description = "이 프로젝트가 정의한 요구사항이며 sequenceNo 오름차순입니다.")
		List<Requirement> requirements,
		@Schema(description = "팀 행 목록이며 반 이름 → 팀 번호 순입니다.")
		List<Team> teams
) {

	@Schema(description = """
			탭 머리의 `제출 6/8` 카운트.

			teamCount = submittedTeamCount + unsubmittedTeamCount 입니다. analysisFailedTeamCount는
			제출한 팀 중 최신 분석이 FAILED인 팀이라 이 등식과 별개입니다.
			""")
	public record Summary(
			@Schema(description = "조회 범위의 팀 수", example = "8")
			long teamCount,
			@Schema(description = "제출을 마친 팀 수", example = "6")
			long submittedTeamCount,
			@Schema(description = "아직 제출하지 않은 팀 수", example = "2")
			long unsubmittedTeamCount,
			@Schema(description = "최신 분석이 실패한 팀 수", example = "1")
			long analysisFailedTeamCount
	) {
	}

	public record Requirement(
			UUID requirementId,
			@Schema(description = "요구사항 식별 키이며 프로젝트 안에서 유일합니다.", example = "HITL_TRIGGER")
			String requirementKey,
			@Schema(description = "표시 순서", example = "1")
			int sequenceNo,
			String title,
			String description
	) {
	}

	@Schema(description = "팀 한 행. 제출·분석이 없으면 submission·analysis가 null입니다.")
	public record Team(
			UUID teamId,
			UUID classId,
			String className,
			@Schema(description = "반 안에서의 팀 번호", example = "3")
			String teamNumber,
			String teamName,
			@Schema(description = "DRAFT · CONFIRMED", example = "CONFIRMED")
			String teamStatus,
			@Schema(description = "아직 아무도 제출하지 않았으면 null입니다. **레코드 존재가 아니라 이 값으로 미제출을 판정합니다.**",
					nullable = true)
			Submission submission,
			@Schema(description = "제출에 매인 최신 분석 시도입니다. 제출이 없거나 아직 분석이 걸리지 않았으면 null입니다.",
					nullable = true)
			Analysis analysis,
			@Schema(description = "요구사항 P/F 판정이며 sequenceNo 오름차순입니다. 분석 전이면 빈 배열입니다.")
			List<RequirementResult> requirementResults,
			@Schema(description = "팀원 개인 행이며 이름 오름차순입니다.")
			List<Member> members
	) {
	}

	public record Submission(
			UUID submissionId,
			Instant submittedAt,
			@Schema(description = "GITHUB_URL · ZIP_WITH_GITLOG", example = "GITHUB_URL")
			String method,
			@Schema(description = "VALIDATING · ACCEPTED · FETCH_FAILED · INVALID", example = "ACCEPTED")
			String status,
			UUID submittedByUserId,
			@Schema(description = "제출을 실행한 팀원 이름", example = "김민준")
			String submittedByName,
			@Schema(description = "GitHub 저장소 주소. ZIP 제출은 null입니다.", nullable = true)
			String repositoryUrl
	) {
	}

	@Schema(description = """
			분석 시도 한 건.

			`status`는 원값 그대로입니다 — PARTIAL을 완료로 접으면 실패도 완료도 아닌 팀이 어느 열에도
			잡히지 않고 사라집니다.
			""")
	public record Analysis(
			UUID analysisJobId,
			@Schema(description = "QUEUED · RUNNING · SUCCEEDED · PARTIAL · FAILED", example = "SUCCEEDED")
			String status,
			@Schema(description = "analysis_job.failure_code 그대로이며 실패가 아니면 null입니다.", nullable = true)
			String failureCode,
			@Schema(description = "실패 사유 원문이며 실패가 아니면 null입니다.", nullable = true)
			String failureReason
	) {
	}

	@Schema(description = """
			요구사항 판정 한 건.

			필드 이름은 `GET /submissions/{submissionId}/analysis/result`의 것과 같습니다 —
			같은 값이 두 API에서 다른 이름으로 나가지 않게 맞췄습니다.
			""")
	public record RequirementResult(
			UUID requirementId,
			String requirementKey,
			String title,
			@Schema(description = "PENDING · PASS · FAIL", example = "PASS")
			String result,
			@Schema(description = "그 판정이 어느 코드에서 나왔는지를 사람이 읽는 한 줄입니다.", nullable = true)
			String evidence,
			@Schema(description = "AI 판정이면 true, 사람이 판정했으면 false입니다.")
			boolean judgedByAi
	) {
	}

	@Schema(description = """
			팀원 한 명의 응시 상태.

			회차의 공식 결과인 최초 응시(INITIAL) 기준입니다. 재시험·다시 보기는 결과 탭 소관이라
			여기 섞지 않습니다.
			""")
	public record Member(
			UUID userId,
			String name,
			@Schema(description = """
					응시 창이 지금 어떤 상태인가로 넷을 가릅니다. **서버가 판정합니다.**

					| 값 | 뜻 |
					|---|---|
					| `DONE` | 응시를 마쳤다 |
					| `OPEN` | 창이 열려 있고 아직 안 봤다 (마감 전) |
					| `MISSED` | 창이 닫히도록 끝내 안 봤다 (마감 후) |
					| `BLOCKED` | 창이 애초에 안 열렸다 — 미제출이거나 분석이 실패했다 |

					`BLOCKED`는 `MISSED`와 다릅니다. 못 본 것이 아니라 볼 수 없었던 것이라 독촉 대상이
					아니며, 이 둘을 합치면 화면이 그 사람에게 무엇을 해야 하는지 말할 수 없습니다.
					""", example = "OPEN")
			String attendanceStatus,
			@Schema(description = "개인 응시 창이 닫히는 시각입니다. `D-2`·`19시간 남음` 같은 표시 문구는 화면이 만듭니다.", nullable = true)
			Instant assessmentCloseAt,
			@Schema(description = "응시를 실제로 마친 시각이며 `DONE`일 때만 값이 있습니다.", nullable = true)
			Instant completedAt
	) {
	}
}
