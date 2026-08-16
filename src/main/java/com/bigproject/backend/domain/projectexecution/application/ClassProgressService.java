package com.bigproject.backend.domain.projectexecution.application;

import com.bigproject.backend.domain.analytics.application.AnalyticsActorGuard;
import com.bigproject.backend.domain.analytics.domain.AnalyticsErrorCode;
import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.projectexecution.domain.ClassProgressQueryRepository;
import com.bigproject.backend.domain.projectexecution.presentation.dto.ClassProgressResponse;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.ManagerViewAccessErrorCode;
import com.bigproject.backend.global.security.ManagerViewScopeGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 프로젝트 상세 '현황' 화면.
 *
 * 제출률과 응시율을 한 응답에 담는다. 응시율의 분모(분석 성공 인원)가 제출 단계의 산출물이라
 * 두 호출로 쪼개면 그 사이에 제출이 바뀔 때 분모가 어긋난다. 제출은 마감 전까지 계속 변한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClassProgressService {

	private final AnalyticsActorGuard analyticsActorGuard;
	private final ManagerViewScopeGuard managerViewScopeGuard;
	private final ClassProgressQueryRepository classProgressQueryRepository;

	public ClassProgressResponse findClassProgress(UUID projectId, int roundNo, String actorEmail) {
		AuthUser actor = analyticsActorGuard.operatorOrManager(actorEmail, "매니저만 프로젝트 현황을 조회할 수 있습니다.");
		if (roundNo < 1) {
			throw new ApiException(AnalyticsErrorCode.ROUND_NO_INVALID);
		}
		ClassProgressQueryRepository.RoundScope round = classProgressQueryRepository.findRound(projectId, roundNo)
				.orElseThrow(() -> new ApiException(roundMissingCode(projectId)));
		if (!round.organizationId().equals(actor.organizationId())) {
			throw new ApiException(AnalyticsErrorCode.PROJECT_CROSS_ORGANIZATION);
		}
		UUID scopedManagerId = scopedManagerId(actor, round.cohortId());

		ClassProgressQueryRepository.RoundSummaryRow summaryRow = classProgressQueryRepository
				.findRoundSummary(round.assessmentRoundId(), round.organizationId(), scopedManagerId);
		ClassProgressResponse.Summary summary = new ClassProgressResponse.Summary(
				summaryRow.targetTraineeCount(),
				summaryRow.submittedCount(),
				summaryRow.analysisTargetCount(),
				summaryRow.analysisSucceededCount(),
				summaryRow.assessmentTargetCount(),
				summaryRow.assessedCount()
		);

		Map<UUID, List<ClassProgressResponse.FailedTeam>> failedTeamsByClass = classProgressQueryRepository
				.findFailedTeams(round.assessmentRoundId(), round.organizationId(), scopedManagerId)
				.stream()
				.collect(Collectors.groupingBy(
						ClassProgressQueryRepository.FailedTeamRow::classId,
						Collectors.mapping(row -> new ClassProgressResponse.FailedTeam(
								row.teamId(),
								row.teamName(),
								row.representativeUserId(),
								row.representativeName(),
								row.failureReason()
						), Collectors.toList())
				));

		List<ClassProgressResponse.ClassProgress> classes = classProgressQueryRepository
				.findClassProgress(round.assessmentRoundId(), round.organizationId(), scopedManagerId)
				.stream()
				.map(row -> new ClassProgressResponse.ClassProgress(
						row.classId(),
						row.className(),
						row.targetTraineeCount(),
						row.submittedCount(),
						row.analysisSucceededCount(),
						row.analysisFailedCount(),
						row.analysisPartialCount(),
						row.analysisInProgressCount(),
						row.assessedCount(),
						row.notAttendedCount(),
						row.sessionIncompleteCount(),
						row.invalidAttemptCount(),
						row.managerNames(),
						failedTeamsByClass.getOrDefault(row.classId(), List.of())
				))
				.toList();

		List<ClassProgressResponse.ConceptMatch> conceptMatches = classProgressQueryRepository
				.findConceptMatches(round.assessmentRoundId(), round.organizationId(), scopedManagerId)
				.stream()
				.map(row -> new ClassProgressResponse.ConceptMatch(
						row.teachesId(),
						row.conceptName(),
						row.analysedTraineeCount(),
						row.matchedTraineeCount(),
						row.unmatchedTeamCount()
				))
				.toList();

		return new ClassProgressResponse(
				round.projectId(),
				round.projectName(),
				round.assessmentRoundId(),
				round.roundNo(),
				round.roundName(),
				round.totalRoundCount(),
				round.submissionDueAt(),
				round.reportPublishMode(),
				round.reportPublished(),
				summary,
				classes,
				conceptMatches
		);
	}

	/**
	 * 목록을 담당 반으로 좁힐 매니저(30차 R3). <b>오퍼레이터는 {@code null}</b>이라 기수 전체를 본다 —
	 * 명단({@code findTraineeRoster})·반 목록과 같은 규칙이다.
	 *
	 * <p>담당 반이 하나도 없는 기수를 매니저가 열면 <b>404로 끊는다.</b> 좁히기만 하면 그 화면은
	 * 「반 0개 · 인원 0명」이 되어, 권한이 없다는 사실이 「아직 데이터가 없다」로 보인다 — 화면이
	 * 기다리라고 안내하게 되고 기다려도 달라지지 않는다. 제출 현황·명단·상세가 같은 자리에서
	 * 같은 코드({@code MANAGER_SCOPE_NOT_FOUND})를 쓴다.
	 */
	private UUID scopedManagerId(AuthUser actor, UUID cohortId) {
		if (actor.role() != Role.MANAGER) {
			return null;
		}
		if (!managerViewScopeGuard.managesCohort(actor.userId(), actor.organizationId(), cohortId)) {
			throw new ApiException(ManagerViewAccessErrorCode.MANAGER_SCOPE_NOT_FOUND);
		}
		return actor.userId();
	}

	/**
	 * 회차를 못 찾았을 때 <b>무엇이 없는지</b>를 가른다(22차 R6).
	 *
	 * <p>회차가 하나라도 있으면 요청한 번호가 없는 것이라 화면은 드롭다운을 되돌리면 된다.
	 * 하나도 없으면 <b>물을 수 있는 회차가 아직 없다</b>는 뜻이라 「회차 준비 중」으로 그리고
	 * 기다려야 한다 — 같은 404를 받고도 화면이 해야 할 일이 정반대다.
	 *
	 * <p>뒤쪽은 22차 이전에 만들어진 프로젝트에서만 난다. 그때는 프로젝트를 만들어도 회차를
	 * 만들지 않았다. 지금은 생성이 회차를 함께 만들므로 새 프로젝트에서는 나오지 않는다.
	 */
	private AnalyticsErrorCode roundMissingCode(UUID projectId) {
		return classProgressQueryRepository.hasAnyRound(projectId)
				? AnalyticsErrorCode.PROJECT_ROUND_NOT_FOUND
				: AnalyticsErrorCode.PROJECT_ROUND_NOT_CREATED;
	}
}
