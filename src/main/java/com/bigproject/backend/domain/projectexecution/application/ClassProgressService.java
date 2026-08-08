package com.bigproject.backend.domain.projectexecution.application;

import com.bigproject.backend.domain.analytics.application.AnalyticsActorGuard;
import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.projectexecution.domain.ClassProgressQueryRepository;
import com.bigproject.backend.domain.projectexecution.presentation.dto.ClassProgressResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
	private final ClassProgressQueryRepository classProgressQueryRepository;

	public ClassProgressResponse findClassProgress(UUID projectId, int roundNo, String actorEmail) {
		AuthUser actor = analyticsActorGuard.operatorOrManager(actorEmail, "매니저만 프로젝트 현황을 조회할 수 있습니다.");
		if (roundNo < 1) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "회차 번호가 올바르지 않습니다.");
		}
		ClassProgressQueryRepository.RoundScope round = classProgressQueryRepository.findRound(projectId, roundNo)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "프로젝트 회차를 찾을 수 없습니다."));
		if (!round.organizationId().equals(actor.organizationId())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 기관의 프로젝트는 조회할 수 없습니다.");
		}

		ClassProgressQueryRepository.RoundSummaryRow summaryRow = classProgressQueryRepository
				.findRoundSummary(round.assessmentRoundId(), round.organizationId());
		ClassProgressResponse.Summary summary = new ClassProgressResponse.Summary(
				summaryRow.targetTraineeCount(),
				summaryRow.submittedCount(),
				summaryRow.analysisTargetCount(),
				summaryRow.analysisSucceededCount(),
				summaryRow.assessmentTargetCount(),
				summaryRow.assessedCount()
		);

		Map<UUID, List<ClassProgressResponse.FailedTeam>> failedTeamsByClass = classProgressQueryRepository
				.findFailedTeams(round.assessmentRoundId(), round.organizationId())
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
				.findClassProgress(round.assessmentRoundId(), round.organizationId())
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
				.findConceptMatches(round.assessmentRoundId(), round.organizationId())
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
}
