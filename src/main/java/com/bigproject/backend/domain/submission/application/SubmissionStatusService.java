package com.bigproject.backend.domain.submission.application;

import com.bigproject.backend.domain.submission.domain.SubmissionErrorCode;
import com.bigproject.backend.domain.submission.domain.SubmissionException;
import com.bigproject.backend.domain.submission.domain.SubmissionStatusQueryRepository;
import com.bigproject.backend.domain.submission.presentation.dto.ProjectSubmissionStatusResponse;
import com.bigproject.backend.global.security.ManagerViewScopeGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * MG-08 제출 현황 탭의 조회 서비스.
 *
 * <p><b>화면이 판정하지 않게 하는 것이 이 클래스의 일이다.</b> 팀 편성 단계, 제출이 열렸는지,
 * 개인 응시 상태 넷, 탭 머리의 카운트가 모두 여기서 나온다. 화면이 배열을 세거나 상태를 조합해
 * 유추하면 같은 규칙이 서버와 화면 두 곳에 생기고, 한쪽만 고치는 순간 조용히 어긋난다.
 *
 * <p>반대로 <b>표시 문구는 만들지 않는다.</b> {@code D-2}·{@code 19시간 남음} 같은 라벨은 화면 소관이라
 * 서버는 {@code assessmentCloseAt} 시각만 준다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubmissionStatusService {

	private final SubmissionStatusQueryRepository repository;
	private final ManagerViewScopeGuard scopeGuard;

	/**
	 * @param classId null이면 매니저 담당 반 전체다. 팀 행마다 className이 실려 화면이 나중에 묶을 수 있다.
	 */
	public ProjectSubmissionStatusResponse findSubmissionStatus(
			String email, UUID projectId, int roundNo, UUID classId) {
		var round = repository.findRound(projectId, roundNo)
				.orElseThrow(() -> new SubmissionException(SubmissionErrorCode.PROJECT_ROUND_NOT_FOUND));

		// 기수 스코프로 담당 여부를 판정한다. 담당 밖이면 MANAGER_SCOPE_NOT_FOUND(404)다 —
		// 남의 반이 '있다'는 사실 자체를 알리지 않는다.
		scopeGuard.requireCohort(email, round.cohortId());
		UUID orgId = round.organizationId();

		var teamRows = repository.findTeams(projectId, round.assessmentRoundId(), orgId, classId);
		var memberRows = repository.findMembers(round.assessmentRoundId(), orgId, classId);
		var requirementRows = repository.findRequirements(projectId, orgId);
		var resultRows = repository.findRequirementResults(round.assessmentRoundId(), orgId, classId);
		long unassignedMemberCount = repository.countUnassignedMembers(projectId, orgId, classId);

		// 팀에 배정되지 않은 사람은 팀 그룹 아래에 그릴 자리가 없어 버린다.
		// 미배정이 남아 있으면 애초에 이 탭이 열리지 않으므로 표에서 사라져 보이는 일도 없다.
		Map<UUID, List<SubmissionStatusQueryRepository.MemberRow>> membersByTeam = memberRows.stream()
				.filter(member -> member.teamId() != null)
				.collect(Collectors.groupingBy(SubmissionStatusQueryRepository.MemberRow::teamId));
		Map<UUID, List<SubmissionStatusQueryRepository.RequirementResultRow>> resultsByTeam = resultRows.stream()
				.collect(Collectors.groupingBy(SubmissionStatusQueryRepository.RequirementResultRow::teamId));

		String stage = teamFormationStage(round.projectLifecycleStatus(), teamRows, unassignedMemberCount);
		boolean closed = "CLOSED".equals(round.projectLifecycleStatus());
		Instant now = Instant.now();

		return new ProjectSubmissionStatusResponse(
				round.projectId(),
				round.projectName(),
				round.assessmentRoundId(),
				round.roundNo(),
				round.roundName(),
				round.submissionDueAt(),
				stage,
				"CONFIRMED".equals(stage) || "CLOSED".equals(stage),
				closed,
				unassignedMemberCount,
				summarize(teamRows),
				requirementRows.stream()
						.map(row -> new ProjectSubmissionStatusResponse.Requirement(
								row.requirementId(), row.requirementKey(), row.sequenceNo(),
								row.title(), row.description()))
						.toList(),
				teamRows.stream()
						.map(team -> toTeam(team, membersByTeam, resultsByTeam, now))
						.toList()
		);
	}

	/**
	 * {@code manager_project_submission_view}의 team_formation_stage와 같은 순서로 가른다.
	 * 종료된 프로젝트가 가장 먼저인 이유는, 끝난 회차는 편성이 어떤 모양이든 더 손댈 것이 없어서다.
	 */
	private String teamFormationStage(
			String lifecycleStatus,
			List<SubmissionStatusQueryRepository.TeamRow> teams,
			long unassignedMemberCount) {
		if ("CLOSED".equals(lifecycleStatus)) {
			return "CLOSED";
		}
		if (teams.isEmpty()) {
			return "NOT_STARTED";
		}
		if (unassignedMemberCount > 0) {
			return "FORMING";
		}
		boolean anyUnconfirmed = teams.stream().anyMatch(team -> !"CONFIRMED".equals(team.teamStatus()));
		return anyUnconfirmed ? "READY_TO_CONFIRM" : "CONFIRMED";
	}

	/** 미제출은 레코드 존재가 아니라 submittedAt으로 판정한다 — 접수 중인 제출을 '냈다'로 세면 안 된다. */
	private ProjectSubmissionStatusResponse.Summary summarize(
			List<SubmissionStatusQueryRepository.TeamRow> teams) {
		long submitted = teams.stream().filter(team -> team.submittedAt() != null).count();
		long analysisFailed = teams.stream().filter(team -> "FAILED".equals(team.analysisStatus())).count();
		return new ProjectSubmissionStatusResponse.Summary(
				teams.size(), submitted, teams.size() - submitted, analysisFailed);
	}

	private ProjectSubmissionStatusResponse.Team toTeam(
			SubmissionStatusQueryRepository.TeamRow team,
			Map<UUID, List<SubmissionStatusQueryRepository.MemberRow>> membersByTeam,
			Map<UUID, List<SubmissionStatusQueryRepository.RequirementResultRow>> resultsByTeam,
			Instant now) {
		return new ProjectSubmissionStatusResponse.Team(
				team.teamId(),
				team.classId(),
				team.className(),
				team.teamNumber(),
				team.teamName(),
				team.teamStatus(),
				team.submittedAt() == null ? null : new ProjectSubmissionStatusResponse.Submission(
						team.submissionId(),
						team.submittedAt(),
						team.submissionMethod(),
						team.submissionStatus(),
						team.submittedByUserId(),
						team.submittedByName(),
						team.repositoryUrl()),
				team.analysisJobId() == null ? null : new ProjectSubmissionStatusResponse.Analysis(
						team.analysisJobId(),
						team.analysisStatus(),
						team.analysisFailureCode(),
						team.analysisFailureReason()),
				resultsByTeam.getOrDefault(team.teamId(), List.of()).stream()
						.sorted(Comparator.comparingInt(
								SubmissionStatusQueryRepository.RequirementResultRow::sequenceNo))
						.map(row -> new ProjectSubmissionStatusResponse.RequirementResult(
								row.requirementId(), row.requirementKey(), row.title(),
								row.result(), row.evidence(), row.judgedByAi()))
						.toList(),
				membersByTeam.getOrDefault(team.teamId(), List.of()).stream()
						.map(member -> new ProjectSubmissionStatusResponse.Member(
								member.userId(),
								member.userName(),
								attendanceStatus(member, now),
								member.assessmentCloseAt(),
								member.completedAt()))
						.toList()
		);
	}

	/**
	 * 응시 창이 지금 어떤 상태인가로 넷을 가른다.
	 *
	 * <p>{@code BLOCKED}를 {@code MISSED}와 나누는 것이 핵심이다. 미제출·분석 실패로 <b>수행 자체가
	 * 만들어지지 않은</b> 사람은 안 본 것이 아니라 볼 수 없었던 것이라, 독촉해도 할 수 있는 일이 없다.
	 * 마감이 지나 창이 닫혔는지({@code MISSED}) 아직 열려 있는지({@code OPEN})는 닫히는 시각으로 가른다.
	 */
	private String attendanceStatus(SubmissionStatusQueryRepository.MemberRow member, Instant now) {
		if (member.completedAt() != null || "COMPLETED".equals(member.completionStatus())) {
			return "DONE";
		}
		if (member.primaryAttemptId() == null || member.assessmentCloseAt() == null) {
			return "BLOCKED";
		}
		if ("EXPIRED".equals(member.primaryAttemptStatus())) {
			return "MISSED";
		}
		return member.assessmentCloseAt().isAfter(now) ? "OPEN" : "MISSED";
	}
}
