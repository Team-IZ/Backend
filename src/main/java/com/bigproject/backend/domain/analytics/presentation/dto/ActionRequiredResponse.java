package com.bigproject.backend.domain.analytics.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = """
		오퍼레이터 대시보드 '조치 필요' 경보.

		경보는 파생 조회이며 해소·무시 상태를 저장하지 않습니다. 원인이 사라지면 경보도 사라지므로
		조치 완료를 기록하는 쓰기 API가 없습니다.

		네 유형은 서로 모양이 달라 각각 별도 필드로 내려갑니다. 해당 경보가 없으면 null이며
		actionCount는 null이 아닌 경보 수입니다.
		""")
public record ActionRequiredResponse(
		UUID cohortId,
		@Schema(description = "실제로 발생한 경보 수이며 화면의 '조치 필요 · N건'입니다.", example = "4")
		int actionCount,
		@Schema(description = "활성 담당 매니저가 없는 반이며 없으면 null입니다.", nullable = true)
		ManagerUnassignedAlert managerUnassigned,
		@Schema(description = "코드 근거를 찾지 못한 팀이 가장 많은 검증 개념이며 없으면 null입니다.", nullable = true)
		ConceptGapAlert conceptGap,
		@Schema(description = "반 인원의 절반을 넘게 2단 이하가 나온 반·개념 중 가장 나쁜 건이며 없으면 null입니다.", nullable = true)
		GroupGapAlert groupGap,
		@Schema(description = "면담 예정일이 가장 오래 지난 반이며 없으면 null입니다.", nullable = true)
		InterviewBacklogAlert interviewBacklog
) {

	@Schema(description = "경보가 가리키는 프로젝트 회차")
	public record RoundRef(
			UUID assessmentRoundId,
			@Schema(description = "회차 번호이며 프로젝트 안에서만 유일합니다.", example = "3")
			int roundNo,
			String roundName,
			UUID projectId,
			@Schema(description = "화면의 '미프 3차'에 해당합니다.", example = "미프 3차")
			String projectName
	) {
	}

	@Schema(description = """
			담당 매니저 미배정. 상시 경보라 회차 맥락이 없습니다.
			면담·독촉을 처리할 사람이 없다는 뜻이라 다른 경보보다 먼저 다뤄야 합니다.
			""")
	public record ManagerUnassignedAlert(
			@Schema(description = "매니저가 없는 반 목록이며 반 이름 오름차순입니다.")
			List<UnassignedClass> classes,
			@Schema(description = "그 반들에 속한 재학 교육생 합계", example = "25")
			long affectedTraineeCount
	) {
	}

	public record UnassignedClass(UUID classId, String className, long traineeCount) {
	}

	@Schema(description = """
			검증 개념 공백. 계획한 개념이 팀 코드에 없어 문제를 만들지 못한 경우입니다.

			판정은 계산이 아니라 읽기입니다. AssessmentProblem.generation_status='NOT_GENERATED'이면
			사유가 NO_MATCHING_CODE_EVIDENCE 하나로 고정돼 있습니다.
			분모는 이해도 검증 세션이 실제 발생한 팀 수입니다.
			""")
	public record ConceptGapAlert(
			RoundRef round,
			UUID teachesId,
			@Schema(description = "검증 개념 이름", example = "State 관리")
			String conceptName,
			@Schema(description = "그 개념의 코드 근거를 찾지 못한 팀 수", example = "6")
			long gapTeamCount,
			@Schema(description = "회차에서 이해도 검증 세션이 발생한 팀 수", example = "8")
			long participatingTeamCount
	) {
	}

	@Schema(description = """
			집단 미달. 반 인원의 절반을 넘는 인원이 한 개념에서 2단 이하인 경우입니다.

			개인 위험 사유가 아니라 반 문제로 분류합니다. 시스템은 표시까지만 하고 이후 처리는
			기관 판단입니다.

			분자는 실제 응시를 마친 인원 중 2단 이하만 셉니다. 미응시·무효 확정은 0단으로
			치환하지 않으므로 분자에서 빠지고 분모(반 인원 전체)에만 남습니다.
			""")
	public record GroupGapAlert(
			RoundRef round,
			UUID classId,
			String className,
			UUID teachesId,
			@Schema(description = "검증 개념 이름", example = "Graph 구성")
			String conceptName,
			@Schema(description = "2단 이하 인원", example = "14")
			long lowLevelCount,
			@Schema(description = "반 인원 전체", example = "25")
			long classMemberCount
	) {
	}

	@Schema(description = """
			면담 적체. 예정일이 지났는데 아직 시작되지 않은 면담입니다.

			지연일은 Interview.planned_at을 기산점으로 하며 아직 시작되지 않은(PENDING) 건만 셉니다.
			pendingInterviewCount는 진행 중을 포함한 미종결 전체라 두 값의 모집단이 다릅니다.
			""")
	public record InterviewBacklogAlert(
			RoundRef round,
			UUID classId,
			String className,
			@Schema(description = "가장 오래 밀린 면담의 지연일", example = "11")
			int maxDelayDays,
			@Schema(description = "종결되지 않은 면담 인원이며 진행 중을 포함합니다.", example = "7")
			long pendingInterviewCount,
			@Schema(description = """
					면담이 아직 생성되지 않아 지연일을 계산할 수 없는 후보 수입니다.
					경보 판정에서 빠지므로 별도로 표시해야 놓치지 않습니다.
					""")
			long notCreatedCount,
			@Schema(description = "예정일이 잡히지 않아 지연일을 계산할 수 없는 면담 수입니다.")
			long unplannedCount
	) {
	}
}
