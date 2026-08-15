package com.bigproject.backend.domain.submission.application;

import com.bigproject.backend.domain.submission.domain.SubmissionErrorCode;
import com.bigproject.backend.domain.submission.domain.SubmissionException;
import com.bigproject.backend.domain.submission.domain.SubmissionStatusQueryRepository;
import com.bigproject.backend.domain.submission.presentation.dto.ProjectSubmissionStatusResponse;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.ManagerViewAccessErrorCode;
import com.bigproject.backend.global.security.ManagerViewScopeGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 *
 * <p><b>조회 범위는 담당 반이다.</b> 기수 스코프 검사는 관문일 뿐이라 그것만 믿고 프로젝트 전체를 읽으면
 * 담당하지 않는 반의 교육생 이름까지 나간다. 그래서 조회 자체를 담당 반으로 좁힌다 —
 * 따라서 팀 수·미배정 인원·편성 단계가 모두 <b>그 매니저가 보는 범위의 값</b>이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubmissionStatusService {

	private final SubmissionStatusQueryRepository repository;
	private final ManagerViewScopeGuard scopeGuard;

	/**
	 * @param classId null이면 매니저 <b>담당 반 전체</b>다. 팀 행마다 className이 실려 화면이 나중에 묶을 수 있다.
	 */
	public ProjectSubmissionStatusResponse findSubmissionStatus(
			String email, UUID projectId, int roundNo, UUID classId) {
		var round = repository.findRound(projectId, roundNo)
				.orElseThrow(() -> new SubmissionException(SubmissionErrorCode.PROJECT_ROUND_NOT_FOUND));

		// 기수 스코프로 담당 여부를 판정한다. 담당 밖이면 MANAGER_SCOPE_NOT_FOUND(404)다 —
		// 남의 반이 '있다'는 사실 자체를 알리지 않는다.
		var actor = scopeGuard.requireCohort(email, round.cohortId());
		UUID orgId = round.organizationId();
		UUID managerUserId = actor.userId();

		// 기수 관문을 통과했어도 반까지 담당한다는 뜻은 아니다. 지정한 반이 담당 밖이면 빈 결과가 아니라
		// 404로 끊는다 — 빈 결과로 두면 화면이 "팀이 없는 회차"로 읽는다.
		if (classId != null && !repository.isClassManagedBy(managerUserId, classId, round.cohortId())) {
			throw new ApiException(ManagerViewAccessErrorCode.MANAGER_SCOPE_NOT_FOUND);
		}

		var teamRows = repository.findTeams(projectId, round.assessmentRoundId(), orgId, managerUserId, classId);
		var memberRows = repository.findMembers(round.assessmentRoundId(), orgId, managerUserId, classId);
		var requirementRows = repository.findRequirements(projectId, orgId);
		var resultRows = repository.findRequirementResults(
				round.assessmentRoundId(), orgId, managerUserId, classId);
		long unassignedMemberCount = repository.countUnassignedMembers(
				projectId, orgId, managerUserId, classId);

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
	// =========================================================================
	// 신규 추가: MG-07 프로젝트 목록 '진행'·'조치' 열 (담당 반 합계)
	// =========================================================================

	/**
	 * MG-07 목록 한 행이 쓰는 값. MG-08 상세({@link #findSubmissionStatus})와 같은 원장을 읽지만
	 * 요구사항 판정·제출 상세는 빼고 <b>목록이 그리는 것만</b> 담는다 — 목록은 행마다 이걸 부르므로
	 * 상세 응답을 그대로 쓰면 회차 수만큼 무거운 조립이 반복된다.
	 *
	 * @param progress    status가 PLANNED면 null이다. 아직 잴 것이 없다
	 * @param actionItems 조치가 필요 없으면 빈 배열. <b>그것이 정상이며 화면은 —로 그린다</b>
	 */
	public record ManagerProjectProgress(Progress progress, List<ActionItem> actionItems) {

		/** 화면의 `응시 58/71` + 그 아래 `C반이 12/23`. */
		public record Progress(
				long assessedCount,
				long targetTraineeCount,
				LaggingClass laggingClass) {
		}

		/**
		 * 담당 반 중 <b>진행률이 가장 낮은 반</b>. 임계값 없이 최솟값 하나를 고른다 —
		 * "A반은 다 냈는데 C반이 밀린" 상황을 합계만으로는 볼 수 없기 때문이다(MG-07 정의).
		 *
		 * <p>담당 반이 하나뿐이면 null이다. 합계가 곧 그 반이라 같은 값을 두 번 쓰게 된다.
		 */
		public record LaggingClass(
				UUID classId,
				String className,
				long assessedCount,
				long targetTraineeCount) {
		}

		/** 화면의 `A반 미제출 2팀` 한 줄. */
		public record ActionItem(
				UUID classId,
				String className,
				String type,
				int teamCount) {

			/** 제출 마감이 지났는데 아직 안 낸 팀. */
			public static final String UNSUBMITTED_TEAMS = "UNSUBMITTED_TEAMS";
			/** 제출은 했지만 코드 분석이 실패한 팀. 재제출을 안내해야 한다. */
			public static final String ANALYSIS_FAILED_TEAMS = "ANALYSIS_FAILED_TEAMS";
		}
	}

	/**
	 * MG-07 목록 한 행의 진행·조치를 계산한다.
	 *
	 * <p><b>조회 범위는 담당 반이다</b> — {@link #findSubmissionStatus}와 같은 리포지토리·같은 가드를
	 * 쓰므로 매니저가 담당하지 않는 반은 애초에 행으로 오지 않는다. 합계도 담당 반 기준이라
	 * 화면의 `58/71`이 그 매니저가 실제로 책임지는 인원이다.
	 *
	 * <p><b>회차가 없거나 아직 시작 전인 프로젝트는 {@code progress}가 null이다.</b> 0/0으로 채우면
	 * 화면이 "아무도 응시 안 함"으로 읽는데, 예정 회차는 그것과 다르다 — 잴 것 자체가 없다.
	 */
	public ManagerProjectProgress findManagerProjectProgress(String email, UUID projectId) {
		// 미니프로젝트는 회차가 프로젝트당 1건이라 round_no=1로 특정된다.
		var round = repository.findRound(projectId, 1).orElse(null);
		if (round == null) {
			return new ManagerProjectProgress(null, List.of());
		}

		var actor = scopeGuard.requireCohort(email, round.cohortId());
		UUID orgId = round.organizationId();
		UUID managerUserId = actor.userId();

		// 아직 열리지 않은 회차는 잴 것이 없다. 조회도 하지 않는다 — 목록은 행마다 이걸 부른다.
		if ("PLANNED".equals(round.projectLifecycleStatus())) {
			return new ManagerProjectProgress(null, List.of());
		}

		// classId=null이면 담당 반 전체다(프로젝트 전체가 아니다).
		var teams = repository.findTeams(projectId, round.assessmentRoundId(), orgId, managerUserId, null);
		if (teams.isEmpty()) {
			return new ManagerProjectProgress(null, List.of());
		}
		var members = repository.findMembers(round.assessmentRoundId(), orgId, managerUserId, null);

		return new ManagerProjectProgress(
				calculateProgress(teams, members),
				calculateActionItems(teams));
	}

	/**
	 * 반별 응시 인원을 세어 합계와 <b>가장 뒤처진 반</b>을 만든다.
	 *
	 * <p>완료 판정은 {@link #attendanceStatus}의 {@code DONE}과 <b>같은 식</b>이다 — 두 화면이
	 * 같은 사람을 두고 한쪽은 완료, 한쪽은 미완료라고 말하면 안 된다.
	 *
	 * <p>팀에 배정되지 않은 사람({@code teamId == null})은 분모에서 뺀다. 어느 반인지 팀을 통해서만
	 * 알 수 있고, 미배정이 남아 있으면 애초에 제출이 열리지 않는다.
	 */
	private ManagerProjectProgress.Progress calculateProgress(
			List<SubmissionStatusQueryRepository.TeamRow> teams,
			List<SubmissionStatusQueryRepository.MemberRow> members) {

		Map<UUID, SubmissionStatusQueryRepository.TeamRow> teamById = teams.stream()
				.collect(Collectors.toMap(SubmissionStatusQueryRepository.TeamRow::teamId, team -> team));

		Map<UUID, long[]> byClass = new LinkedHashMap<>();   // classId → [완료, 전체]
		Map<UUID, String> classNames = new HashMap<>();
		long assessed = 0;
		long target = 0;

		for (var member : members) {
			var team = member.teamId() == null ? null : teamById.get(member.teamId());
			if (team == null) {
				continue;
			}
			boolean done = member.completedAt() != null || "COMPLETED".equals(member.completionStatus());
			long[] counts = byClass.computeIfAbsent(team.classId(), key -> new long[2]);
			classNames.putIfAbsent(team.classId(), team.className());
			if (done) {
				counts[0]++;
				assessed++;
			}
			counts[1]++;
			target++;
		}

		if (target == 0) {
			return null;
		}

		// 담당 반이 하나뿐이면 합계가 곧 그 반이라 따로 집어 봐야 의미가 없다.
		ManagerProjectProgress.LaggingClass lagging = byClass.size() < 2 ? null : byClass.entrySet().stream()
				.min(Comparator.comparingDouble(entry -> (double) entry.getValue()[0] / entry.getValue()[1]))
				.map(entry -> new ManagerProjectProgress.LaggingClass(
						entry.getKey(), classNames.get(entry.getKey()),
						entry.getValue()[0], entry.getValue()[1]))
				.orElse(null);

		return new ManagerProjectProgress.Progress(assessed, target, lagging);
	}

	/**
	 * 반별 미제출·분석 실패 팀 수를 센다. 0건인 반은 항목을 만들지 않는다 —
	 * <b>조치 열이 비는 것이 정상</b>이며 화면은 그때 —를 그린다.
	 *
	 * <p>미제출 판정은 {@link #summarize}와 같이 {@code submittedAt}으로 한다. 레코드 존재로 세면
	 * 접수 중인 제출을 '냈다'로 세게 된다.
	 */
	private List<ManagerProjectProgress.ActionItem> calculateActionItems(
			List<SubmissionStatusQueryRepository.TeamRow> teams) {

		Map<UUID, List<SubmissionStatusQueryRepository.TeamRow>> byClass = teams.stream()
				.collect(Collectors.groupingBy(SubmissionStatusQueryRepository.TeamRow::classId,
						LinkedHashMap::new, Collectors.toList()));

		List<ManagerProjectProgress.ActionItem> items = new ArrayList<>();
		byClass.forEach((classId, classTeams) -> {
			String className = classTeams.get(0).className();
			long unsubmitted = classTeams.stream().filter(team -> team.submittedAt() == null).count();
			if (unsubmitted > 0) {
				items.add(new ManagerProjectProgress.ActionItem(classId, className,
						ManagerProjectProgress.ActionItem.UNSUBMITTED_TEAMS, Math.toIntExact(unsubmitted)));
			}
			long analysisFailed = classTeams.stream()
					.filter(team -> "FAILED".equals(team.analysisStatus())).count();
			if (analysisFailed > 0) {
				items.add(new ManagerProjectProgress.ActionItem(classId, className,
						ManagerProjectProgress.ActionItem.ANALYSIS_FAILED_TEAMS, Math.toIntExact(analysisFailed)));
			}
		});
		return items;
	}
}