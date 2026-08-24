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
		//
		// 32차 R11로 FORMING에서도 표가 열리므로 "탭이 안 열려서 안 보인다"는 근거는 더 이상
		// 성립하지 않는다. 대신 그 인원은 unassignedMemberCount로 따로 나가므로 화면에서
		// 사라지지 않는다 — 팀 행 아래가 아니라 별도 자리에 세는 것이 맞다.
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
				submissionOpened(round.projectLifecycleStatus(), teamRows),
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
	 * 제출 현황을 그릴 수 있는가.
	 *
	 * <h2>🔴 32차 R11 — 팀 확정이 아니라 <b>제출 수령 여부</b>가 기준이다</h2>
	 *
	 * <p>종전에는 {@code stage}가 {@code CONFIRMED}·{@code CLOSED}일 때만 참이었다. 즉 이름은
	 * "제출이 열렸는가"인데 실제로 답한 것은 <b>"팀 편성이 확정됐는가"</b>였다.
	 *
	 * <p><b>그 둘은 서버에서 이어져 있지 않다.</b> {@code SubmissionService.requireSubmittableRound}가
	 * 제출을 받을지 정할 때 보는 것은 <b>회차가 열려 있는지와 마감뿐</b>이고 팀 상태는 보지 않는다.
	 * 그래서 팀이 전부 {@code DRAFT}인 회차에도 제출이 정상적으로 들어온다.
	 *
	 * <p>실제로 32차에서 팀 7개가 전부 {@code DRAFT}(→ {@code READY_TO_CONFIRM} → {@code false})인데
	 * <b>5팀이 이미 제출하고 분석까지 끝났고 리포트도 발행된</b> 회차가 관측됐다. 화면은 스펙대로
	 * 이 값만 보고 판정했으므로 제출 5건·분석 5건·응시 18명을 통째로 가렸다.
	 *
	 * <p>그래서 기준을 <b>그릴 것이 있는가</b>로 맞춘다.
	 * <ul>
	 *   <li>{@code PLANNED} — 아직 시작 전이라 제출이 있을 수 없다. 빈 상태가 맞다</li>
	 *   <li>팀 0개 — 그릴 행이 없다. 빈 상태가 맞다({@code NOT_STARTED}와 같은 조건)</li>
	 *   <li>그 밖 — 제출을 받았거나 받는 중이다. 표를 그린다</li>
	 * </ul>
	 *
	 * <p>"지금 이 순간 제출을 받고 있는가"로 정의하지 않은 이유는 <b>마감 뒤에 다시 숨기 때문</b>이다.
	 * 매니저가 제출 현황을 보는 시점은 대개 마감 후다 — 그때 표가 사라지면 같은 사고가 반복된다.
	 *
	 * <p>편성 진행 상황은 {@code teamFormationStage}가 그대로 답한다. 두 값을 분리해 두면 화면이
	 * "표를 그릴까"와 "편성이 어디까지 됐나"를 각각 읽을 수 있다.
	 */
	private boolean submissionOpened(
			String lifecycleStatus,
			List<SubmissionStatusQueryRepository.TeamRow> teams) {
		return !"PLANNED".equals(lifecycleStatus) && !teams.isEmpty();
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
	 *
	 * <p><b>완료는 {@code completionStatus}로만 가른다.</b> 종전에는 {@code completedAt}(=
	 * {@code measurement_attempt.terminal_at})이 있으면 완료로 봤는데, 그 컬럼은 FAILED·EXPIRED에서도
	 * 채워지므로 지난 회차의 전원이 {@code DONE}이 됐다 — 아래 {@code EXPIRED} 분기가 한 번도 닿지
	 * 않는 죽은 코드였던 것이 그 증거다.
	 */
	private String attendanceStatus(SubmissionStatusQueryRepository.MemberRow member, Instant now) {
		if ("COMPLETED".equals(member.completionStatus())) {
			return "DONE";
		}
		if (member.primaryAttemptId() == null || member.assessmentCloseAt() == null) {
			return "BLOCKED";
		}
		// 분석 실패는 창이 열렸다 닫힌 것이 아니라 볼 수 없었던 경우다. close_at이 남아 있어도
		// MISSED로 세면 "봤어야 했는데 안 봤다"가 되어 독촉 대상으로 잘못 읽힌다.
		if ("FAILED".equals(member.primaryAttemptStatus())) {
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

		/**
		 * 화면의 `응시 58/71` + 그 아래 `C반이 12/23`.
		 *
		 * <p>{@code targetTraineeCount}는 담당 반 전체가 아니라 <b>응시 대상</b>이다 — 미제출·분석
		 * 실패로 문항이 만들어지지 않은 사람은 응시할 방법이 없어 분모가 아니다. 그 인원은
		 * {@code blockedCount}로 따로 온다. 반별 현황(class-progress)의 응시율과 같은 기준이다.
		 */
		public record Progress(
				long assessedCount,
				long targetTraineeCount,
				long blockedCount,
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
				int teamCount,
				/** 걸린 팀 목록. 36차 R2 — teamCount만으로는 어느 팀인지 알 수 없어서 붙였다. */
				List<TeamRef> teams) {

			/** 제출 마감이 지났는데 아직 안 낸 팀. */
			public static final String UNSUBMITTED_TEAMS = "UNSUBMITTED_TEAMS";
			/** 제출은 했지만 코드 분석이 실패한 팀. 재제출을 안내해야 한다. */
			public static final String ANALYSIS_FAILED_TEAMS = "ANALYSIS_FAILED_TEAMS";
			/**
			 * 면담이 아직 안 끝난 인원(34차 R7①).
			 *
			 * <p><b>{@code teamCount}에 팀이 아니라 사람 수가 들어간다.</b> 앞의 둘은 팀 단위
			 * 조치이고 면담은 사람 단위인데, 화면이 이미 그 자리를 「숫자 + 단위」로 그리고 있어
			 * 필드를 새로 내지 않고 같은 자리를 쓴다(프론트 제안). 단위는 화면이 {@code type}으로
			 * 가른다.
			 *
			 * <p>종료된 회차의 조치 열이 늘 비어 있던 것을 메운다 — 회차 6개 중 5개가 종료
			 * 상태라 표의 5/6이 「—」였다.
			 */
			public static final String INTERVIEW_BACKLOG = "INTERVIEW_BACKLOG";
		}

		/** actionItems[].teams[] 한 건. */
		public record TeamRef(UUID teamId, String teamName) {
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
	/**
	 * MG-07 목록 <b>여러 행</b>의 진행·조치를 한 번에 계산한다(34차 R8).
	 *
	 * <h2>왜 묶었는가</h2>
	 *
	 * <p>종전에는 목록이 행마다 {@link #findManagerProjectProgress}를 불렀고, 그 안에서 회차·팀·개인
	 * 조회 3건이 돌았다. 회차가 늘면 그대로 곱해져 <b>회차당 약 0.86초</b>가 붙었다(프론트 34차 R8).
	 * 이제 회차 조회 1건 + 집계 1건으로 <b>목록 전체가 2건</b>이다.
	 *
	 * <p>판정 기준은 종전과 같다 — 계산을 자바에서 SQL로 옮겼을 뿐이라 같은 회차에서 같은 값이
	 * 나와야 한다. {@code PLANNED}가 {@code null}인 것도, 팀 미배정이 분모에서 빠지는 것도 그대로다.
	 *
	 * @return 프로젝트별 진행·조치. <b>키가 없는 프로젝트는 잴 것이 없다는 뜻</b>이며 호출자가
	 *         빈 값으로 그린다
	 */
	public Map<UUID, ManagerProjectProgress> findManagerProjectProgress(
			String email, List<UUID> projectIds, UUID classId) {
		if (projectIds.isEmpty()) {
			return Map.of();
		}
		// 미니프로젝트는 회차가 프로젝트당 1건이라 round_no=1로 특정된다.
		var rounds = repository.findRounds(projectIds, 1).stream()
				// 아직 열리지 않은 회차는 잴 것이 없다. 0/0으로 채우면 화면이 "아무도 응시 안 함"으로
				// 읽는데, 예정 회차는 그것과 다르다.
				.filter(round -> !"PLANNED".equals(round.projectLifecycleStatus()))
				.toList();
		if (rounds.isEmpty()) {
			return Map.of();
		}

		Map<UUID, ManagerProjectProgress> result = new LinkedHashMap<>();
		// 기수·기관이 섞인 목록은 스코프가 다르므로 묶어서 각각 집계한다. 보통은 한 벌이다.
		var byScope = rounds.stream().collect(Collectors.groupingBy(
				round -> List.of(round.cohortId(), round.organizationId()),
				LinkedHashMap::new, Collectors.toList()));

		byScope.forEach((scope, scopeRounds) -> {
			UUID cohortId = (UUID) scope.get(0);
			UUID orgId = (UUID) scope.get(1);
			var actor = scopeGuard.requireCohort(email, cohortId);
			UUID managerUserId = actor.userId();

			// 담당 밖 반을 지정했으면 빈 결과가 아니라 404다 — 빈 결과로 두면 화면이
			// "팀이 없는 회차"로 잘못 읽는다. 목록 전체에 한 번만 검사한다.
			if (classId != null && !repository.isClassManagedBy(managerUserId, classId, cohortId)) {
				throw new ApiException(ManagerViewAccessErrorCode.MANAGER_SCOPE_NOT_FOUND);
			}

			var roundIds = scopeRounds.stream()
					.map(SubmissionStatusQueryRepository.RoundScope::assessmentRoundId).toList();
			var byRound = repository
					.findManagerProgressAggregates(roundIds, orgId, managerUserId, classId).stream()
					.collect(Collectors.groupingBy(
							SubmissionStatusQueryRepository.ManagerProgressAggregate::assessmentRoundId));

			for (var round : scopeRounds) {
				var rows = byRound.get(round.assessmentRoundId());
				if (rows == null || rows.isEmpty()) {
					continue;
				}
				// 행 grain은 팀이다 — 반 하나로 다시 묶어야 반 단위 합계·조치를 만들 수 있다.
				var byClass = rows.stream().collect(Collectors.groupingBy(
						SubmissionStatusQueryRepository.ManagerProgressAggregate::classId,
						LinkedHashMap::new, Collectors.toList()));
				result.put(round.projectId(),
						new ManagerProjectProgress(progressOf(byClass), actionItemsOf(byClass)));
			}
		});
		return result;
	}

	/**
	 * 반별 팀 행에서 합계와 <b>가장 뒤처진 반</b>을 만든다. 기준은 {@link #calculateProgress}와 같다.
	 *
	 * <p>{@code assessedCount}·{@code targetCount}는 반 전체 값이 그 반의 팀 행마다 반복돼 있으므로
	 * 반마다 첫 행 하나만 읽는다.
	 */
	private ManagerProjectProgress.Progress progressOf(
			Map<UUID, List<SubmissionStatusQueryRepository.ManagerProgressAggregate>> byClass) {
		long assessed = 0;
		long target = 0;
		long blocked = 0;
		long eligible = 0;
		List<SubmissionStatusQueryRepository.ManagerProgressAggregate> classTotals = new ArrayList<>();
		for (var rows : byClass.values()) {
			var first = rows.get(0);
			assessed += first.assessedCount();
			target += first.targetCount();
			blocked += first.blockedCount();
			eligible += first.eligibleCount();
			classTotals.add(first);
		}
		// null은 「잴 것이 없다」는 뜻으로만 쓴다. 분모가 0이어도 사람이 있으면 그것은 「전원이 응시
		// 자체를 못 했다」는 사실이며, 0/0 + blocked N으로 말해야 화면이 예정 회차와 구분할 수 있다.
		if (eligible == 0) {
			return null;
		}
		// 담당 반이 하나뿐이면 합계가 곧 그 반이라 따로 집어 봐야 의미가 없다.
		var withMembers = classTotals.stream().filter(row -> row.targetCount() > 0).toList();
		ManagerProjectProgress.LaggingClass lagging = withMembers.size() < 2 ? null : withMembers.stream()
				.min(Comparator.comparingDouble(
						row -> (double) row.assessedCount() / row.targetCount()))
				.map(row -> new ManagerProjectProgress.LaggingClass(
						row.classId(), row.className(), row.assessedCount(), row.targetCount()))
				.orElse(null);
		return new ManagerProjectProgress.Progress(assessed, target, blocked, lagging);
	}

	/** 0건인 반·유형은 항목을 만들지 않는다 — 조치 열이 비는 것이 정상이며 화면은 그때 —를 그린다. */
	private List<ManagerProjectProgress.ActionItem> actionItemsOf(
			Map<UUID, List<SubmissionStatusQueryRepository.ManagerProgressAggregate>> byClass) {
		List<ManagerProjectProgress.ActionItem> items = new ArrayList<>();
		byClass.forEach((classId, rows) -> {
			String className = rows.get(0).className();
			var unsubmittedTeams = rows.stream()
					.filter(row -> row.submittedAt() == null)
					.map(row -> new ManagerProjectProgress.TeamRef(row.teamId(), row.teamName()))
					.toList();
			if (!unsubmittedTeams.isEmpty()) {
				items.add(new ManagerProjectProgress.ActionItem(classId, className,
						ManagerProjectProgress.ActionItem.UNSUBMITTED_TEAMS,
						unsubmittedTeams.size(), unsubmittedTeams));
			}
			var analysisFailedTeams = rows.stream()
					.filter(row -> "FAILED".equals(row.analysisStatus()))
					.map(row -> new ManagerProjectProgress.TeamRef(row.teamId(), row.teamName()))
					.toList();
			if (!analysisFailedTeams.isEmpty()) {
				items.add(new ManagerProjectProgress.ActionItem(classId, className,
						ManagerProjectProgress.ActionItem.ANALYSIS_FAILED_TEAMS,
						analysisFailedTeams.size(), analysisFailedTeams));
			}
			// 면담 대기는 사람 단위라 팀 목록이 없다 — teamCount 자리에도 사람 수가 들어간다.
			int interviewBacklogCount = rows.get(0).interviewBacklogCount();
			if (interviewBacklogCount > 0) {
				items.add(new ManagerProjectProgress.ActionItem(classId, className,
						ManagerProjectProgress.ActionItem.INTERVIEW_BACKLOG, interviewBacklogCount, List.of()));
			}
		});
		return items;
	}

	public ManagerProjectProgress findManagerProjectProgress(String email, UUID projectId, UUID classId) {
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

		// classId를 지정했는데 담당 밖이면 findSubmissionStatus와 같은 규칙으로 막는다 —
		// 목록에서 필터를 걸었을 때만 이 검사를 타므로, 목록 진입 시 매번 담당 반을 재확인하지 않는다.
		if (classId != null && !repository.isClassManagedBy(managerUserId, classId, round.cohortId())) {
			throw new ApiException(ManagerViewAccessErrorCode.MANAGER_SCOPE_NOT_FOUND);
		}

		// classId=null이면 담당 반 전체, 지정하면 그 반 하나로 좁힌다.
		var teams = repository.findTeams(projectId, round.assessmentRoundId(), orgId, managerUserId, classId);
		if (teams.isEmpty()) {
			return new ManagerProjectProgress(null, List.of());
		}
		var members = repository.findMembers(round.assessmentRoundId(), orgId, managerUserId, classId);

		return new ManagerProjectProgress(
				calculateProgress(teams, members, round.submissionDueAt()),
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
	 *
	 * <p><b>분모는 응시 대상이다.</b> 미제출·분석 실패로 문항이 만들어지지 않은 사람은 응시할 방법이
	 * 없으므로 분모에서 빠지고 {@code blockedCount}로 따로 센다. 분석은 팀 단위 산출물이라 그 사람의
	 * 팀 행에서 읽는다 — {@link #findManagerProjectProgress(String, List, UUID)}가 개인 행의
	 * {@code analysis_status}로 세는 것과 같은 값이다.
	 */
	private ManagerProjectProgress.Progress calculateProgress(
			List<SubmissionStatusQueryRepository.TeamRow> teams,
			List<SubmissionStatusQueryRepository.MemberRow> members,
			Instant submissionDueAt) {

		Map<UUID, SubmissionStatusQueryRepository.TeamRow> teamById = teams.stream()
				.collect(Collectors.toMap(SubmissionStatusQueryRepository.TeamRow::teamId, team -> team));

		Map<UUID, long[]> byClass = new LinkedHashMap<>();   // classId → [완료, 응시 대상]
		Map<UUID, String> classNames = new HashMap<>();
		long assessed = 0;
		long target = 0;
		long blocked = 0;
		long eligible = 0;

		for (var member : members) {
			var team = member.teamId() == null ? null : teamById.get(member.teamId());
			if (team == null) {
				continue;
			}
			eligible++;
			long[] counts = byClass.computeIfAbsent(team.classId(), key -> new long[2]);
			classNames.putIfAbsent(team.classId(), team.className());

			// 세 갈래다. 응시 대상 · 응시 불가 · 아직 어느 쪽도 아님(제출 대기·분석 중).
			// 마지막 갈래를 분모에 넣으면 진행 중인 회차의 응시율이 0%로 시작하고,
			// 응시 불가에 넣으면 아직 낼 수 있는 사람을 못 낸 사람으로 세게 된다.
			if (assessable(team)) {
				if ("COMPLETED".equals(member.completionStatus())) {
					counts[0]++;
					assessed++;
				}
				counts[1]++;
				target++;
			} else if (blockedFromAssessment(team, submissionDueAt)) {
				blocked++;
			}
		}

		if (eligible == 0) {
			return null;
		}

		// 담당 반이 하나뿐이면 합계가 곧 그 반이라 따로 집어 봐야 의미가 없다.
		// 응시 대상이 0인 반은 진행률을 만들 수 없어 '가장 뒤처진 반' 후보에서 뺀다.
		var withTargets = byClass.entrySet().stream().filter(entry -> entry.getValue()[1] > 0).toList();
		ManagerProjectProgress.LaggingClass lagging = withTargets.size() < 2 ? null : withTargets.stream()
				.min(Comparator.comparingDouble(entry -> (double) entry.getValue()[0] / entry.getValue()[1]))
				.map(entry -> new ManagerProjectProgress.LaggingClass(
						entry.getKey(), classNames.get(entry.getKey()),
						entry.getValue()[0], entry.getValue()[1]))
				.orElse(null);

		return new ManagerProjectProgress.Progress(assessed, target, blocked, lagging);
	}

	/**
	 * 마감이 지나도록 안 냈거나 분석이 실패한 팀의 팀원은 응시할 문항 자체가 없다.
	 *
	 * <p><b>마감 전 미제출은 여기 들어오지 않는다.</b> 아직 낼 수 있으므로 확정된 사실이 아니며,
	 * 그렇게 세면 진행 중인 회차가 통째로 응시 불가가 된다(그린컴퍼니 7기 미프 4차에서 249명
	 * 전원이 그랬다). 그 사람들은 분모도 응시 불가도 아니고 전체 인원에만 남는다. 뷰의
	 * {@code submission_deadline_status}가 {@code MISSED}와 {@code OPEN}을 가르는 축과 같다.
	 *
	 * <p>{@code PARTIAL}은 막지 않는다 — 일부 개념만 문항이 생성된 경우이고 그 문항으로 응시할 수
	 * 있어서다. 응시할 수 있는 사람을 분모에서 빼면 응시율이 부풀려진다.
	 */
	/** 분석이 끝나 문항이 만들어졌는가. 배치 SQL의 {@code analysis_status IN ('SUCCEEDED','PARTIAL')}과 같다. */
	private boolean assessable(SubmissionStatusQueryRepository.TeamRow team) {
		return "SUCCEEDED".equals(team.analysisStatus()) || "PARTIAL".equals(team.analysisStatus());
	}

	private boolean blockedFromAssessment(
			SubmissionStatusQueryRepository.TeamRow team, Instant submissionDueAt) {
		if ("FAILED".equals(team.analysisStatus())) {
			return true;
		}
		return team.submittedAt() == null
				&& submissionDueAt != null && submissionDueAt.isBefore(Instant.now());
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
			List<ManagerProjectProgress.TeamRef> unsubmittedTeams = classTeams.stream()
					.filter(team -> team.submittedAt() == null)
					.map(team -> new ManagerProjectProgress.TeamRef(team.teamId(), team.teamName()))
					.toList();
			if (!unsubmittedTeams.isEmpty()) {
				items.add(new ManagerProjectProgress.ActionItem(classId, className,
						ManagerProjectProgress.ActionItem.UNSUBMITTED_TEAMS, unsubmittedTeams.size(), unsubmittedTeams));
			}
			List<ManagerProjectProgress.TeamRef> analysisFailedTeams = classTeams.stream()
					.filter(team -> "FAILED".equals(team.analysisStatus()))
					.map(team -> new ManagerProjectProgress.TeamRef(team.teamId(), team.teamName()))
					.toList();
			if (!analysisFailedTeams.isEmpty()) {
				items.add(new ManagerProjectProgress.ActionItem(classId, className,
						ManagerProjectProgress.ActionItem.ANALYSIS_FAILED_TEAMS, analysisFailedTeams.size(), analysisFailedTeams));
			}
		});
		return items;
	}
}