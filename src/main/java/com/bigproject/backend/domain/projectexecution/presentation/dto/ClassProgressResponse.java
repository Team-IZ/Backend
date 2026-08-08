package com.bigproject.backend.domain.projectexecution.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = """
		프로젝트 회차의 반별 제출 → 분석 → 응시 진행 현황.

		단계별 깔때기라 각 단계의 분모가 앞 단계의 분자입니다.
		제출률은 submittedCount / targetTraineeCount,
		응시율은 assessedCount / analysisSucceededCount 입니다.

		제출은 팀 단위 원장이지만 이 화면은 인원 기준으로 환산합니다.
		""")
public record ClassProgressResponse(
		UUID projectId,
		String projectName,
		UUID assessmentRoundId,
		@Schema(description = "회차 번호이며 프로젝트 안에서만 유일합니다.", example = "1")
		int roundNo,
		String roundName,
		@Schema(description = "이 프로젝트에 속한 전체 회차 수입니다. 화면의 'N차 / M회' 표기에 씁니다.", example = "6")
		int totalRoundCount,
		@Schema(description = "제출 마감 시각입니다.")
		Instant submissionDueAt,
		@Schema(description = "리포트 발행 방식입니다. 현재는 마감 후 전 반을 한 번에 발행하는 ROUND_BATCH만 존재합니다.", example = "ROUND_BATCH")
		String reportPublishMode,
		@Schema(description = "이 회차 리포트가 발행됐는지 여부입니다. false면 화면에 '미발행'을 표시합니다.")
		boolean reportPublished,
		@Schema(description = "회차 전체 진행 현황 합계입니다. classes[]를 합산한 것이 아니라 회차 단위로 직접 집계합니다.")
		Summary summary,
		@Schema(description = "반 행 목록이며 반 이름 오름차순입니다.")
		List<ClassProgress> classes,
		@Schema(description = "검증 개념별 코드 매칭 현황입니다.")
		List<ConceptMatch> conceptMatches
) {

	@Schema(description = """
			회차 전체 합계.

			analysisTargetCount는 submittedCount와, assessmentTargetCount는 analysisSucceededCount와
			값이 같습니다 — 단계별 분모를 필드 이름으로도 드러내기 위해 따로 둡니다.
			제출률은 submittedCount / targetTraineeCount, 응시율은 assessedCount / analysisTargetCount 입니다.
			""")
	public record Summary(
			@Schema(description = "이번 회차 수행 대상 교육생 수(기수 총원)이며 제출률의 분모입니다.", example = "250")
			long targetTraineeCount,
			@Schema(description = "제출을 마친 교육생 수", example = "231")
			long submittedCount,
			@Schema(description = "분석 대상 교육생 수이며 submittedCount와 같습니다.", example = "231")
			long analysisTargetCount,
			@Schema(description = "분석이 성공한 교육생 수", example = "223")
			long analysisSucceededCount,
			@Schema(description = "응시 대상 교육생 수이며 analysisSucceededCount와 같습니다.", example = "223")
			long assessmentTargetCount,
			@Schema(description = "응시(INITIAL 완료)를 마친 교육생 수", example = "198")
			long assessedCount
	) {
	}

	@Schema(description = """
			반 한 행.

			분석 상태는 네 갈래를 모두 내려줍니다. PARTIAL을 분석 완료로 세지 않기로 했으므로
			완료·실패만으로는 제출 인원과 등식이 성립하지 않습니다. 네 값을 모두 보면
			어느 열에도 잡히지 않고 사라지는 인원이 없습니다.

			submittedCount = analysisSucceededCount + analysisFailedCount
			               + analysisPartialCount + analysisInProgressCount
			""")
	public record ClassProgress(
			UUID classId,
			String className,
			@Schema(description = "회차 수행 대상 교육생 수이며 제출률의 분모입니다.", example = "25")
			long targetTraineeCount,
			@Schema(description = "소속 팀이 제출을 마친 교육생 수", example = "24")
			long submittedCount,
			@Schema(description = "분석이 성공한 교육생 수이며 응시율의 분모입니다. PARTIAL은 포함하지 않습니다.", example = "23")
			long analysisSucceededCount,
			@Schema(description = "분석이 실패한 교육생 수", example = "1")
			long analysisFailedCount,
			@Schema(description = """
					분석이 부분 성공(PARTIAL)한 교육생 수입니다.
					분석 완료로 세지 않으므로 응시율 분모에서 빠집니다. 이 값이 0이 아닌데
					응시율이 100%를 넘으면 그 인원이 응시를 마쳤다는 뜻입니다.
					""", example = "0")
			long analysisPartialCount,
			@Schema(description = "분석이 대기·진행 중인 교육생 수", example = "0")
			long analysisInProgressCount,
			@Schema(description = "최초 응시(INITIAL)를 완료한 교육생 수", example = "20")
			long assessedCount,
			@Schema(description = "미응시(NOT_ATTENDED) 교육생 수")
			long notAttendedCount,
			@Schema(description = "중단(SESSION_INCOMPLETE) 교육생 수")
			long sessionIncompleteCount,
			@Schema(description = "무효 확정(CONFIRMED_INVALID) 교육생 수이며 무효 확인 중은 세지 않습니다.")
			long invalidAttemptCount,
			@Schema(description = """
					활성 담당 매니저 이름 목록입니다. 한 반에 여러 명이 배정될 수 있습니다.
					비어 있으면 화면의 '담당 없음'이며 대시보드의 미배정 경보와 같은 조건입니다.
					""")
			List<String> managerNames,
			@Schema(description = """
					분석이 실패한 팀 목록입니다. 크기는 analysisFailedCount와 같지 않을 수 있습니다 —
					analysisFailedCount는 인원 기준이고 이 목록은 팀 기준입니다(한 팀에 여러 팀원).
					""")
			List<FailedTeam> failedTeams
	) {
	}

	@Schema(description = """
			분석이 실패한 팀 한 건.

			대표자는 그 팀·회차의 최신 제출(submission.submitted_by)을 실행한 사용자이며,
			실패 판정은 그 제출에 매인 최신 분석 시도(analysis_job) 기준입니다.
			""")
	public record FailedTeam(
			UUID teamId,
			String teamName,
			UUID representativeUserId,
			@Schema(description = "대표자(제출을 실행한 사용자) 이름")
			String representativeName,
			@Schema(description = "analysis_job.failure_reason 그대로입니다. null일 수 있습니다.")
			String failureReason
	) {
	}

	@Schema(description = """
			검증 개념별 코드 매칭.

			문제는 팀 공용이라 팀 단위 매칭 판정을 팀원 인원으로 펼쳐 셉니다.
			매칭 실패는 AssessmentProblem.generation_status='NOT_GENERATED'이며
			사유는 NO_MATCHING_CODE_EVIDENCE 하나로 고정돼 있습니다.
			""")
	public record ConceptMatch(
			UUID teachesId,
			@Schema(description = "검증 개념 이름", example = "Snapshot 개념과 구성 요소")
			String conceptName,
			@Schema(description = "분석에 성공한 교육생 수이며 매칭률의 분모입니다.", example = "227")
			long analysedTraineeCount,
			@Schema(description = "그 개념의 문제를 받은 교육생 수", example = "84")
			long matchedTraineeCount,
			@Schema(description = "그 개념이 코드에서 발견되지 않아 전원이 문제를 받지 못한 팀 수", example = "31")
			long unmatchedTeamCount
	) {
	}
}
