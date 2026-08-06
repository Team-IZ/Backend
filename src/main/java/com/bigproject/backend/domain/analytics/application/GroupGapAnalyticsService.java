package com.bigproject.backend.domain.analytics.application;

import com.bigproject.backend.domain.analytics.domain.GroupGapPolicy;
import com.bigproject.backend.domain.analytics.domain.OperationalActionQueryRepository;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeQueryRepository;
import com.bigproject.backend.domain.analytics.presentation.dto.GroupGapResponse;
import com.bigproject.backend.domain.auth.domain.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * 기수 전체의 집단 미달 목록.
 *
 * 대시보드 경보와 같은 질의를 쓰되 최댓값 한 건이 아니라 미달 조합 전부를 돌려준다.
 * 발행된 리포트에 의존하지 않고 원천에서 실시간 집계하므로 리포트 발행 전에도 값이 나온다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GroupGapAnalyticsService {

	private final AnalyticsActorGuard analyticsActorGuard;
	private final RiskTraineeQueryRepository riskTraineeQueryRepository;
	private final OperationalActionQueryRepository operationalActionQueryRepository;

	public GroupGapResponse findGroupGaps(UUID cohortId, String actorEmail) {
		AuthUser actor = analyticsActorGuard.operatorOrManager(actorEmail, "매니저만 집단 미달 목록을 조회할 수 있습니다.");
		RiskTraineeQueryRepository.CohortScope cohort = riskTraineeQueryRepository.findCohortScope(cohortId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "기수를 찾을 수 없습니다."));
		analyticsActorGuard.requireSameOrganization(cohort.organizationId(), actor);

		List<OperationalActionQueryRepository.GroupGapRow> evaluated =
				operationalActionQueryRepository.findGroupGaps(cohortId, cohort.organizationId());
		List<GroupGapResponse.GroupGapRow> gaps = evaluated.stream()
				.filter(row -> GroupGapPolicy.underperforming(row.lowLevelCount(), row.classMemberCount()))
				.map(row -> new GroupGapResponse.GroupGapRow(
						row.round().assessmentRoundId(),
						row.round().roundNo(),
						row.round().roundName(),
						row.round().projectId(),
						row.round().projectName(),
						row.classId(),
						row.className(),
						row.teachesId(),
						row.conceptName(),
						row.lowLevelCount(),
						row.classMemberCount(),
						GroupGapPolicy.lowLevelRate(row.lowLevelCount(), row.classMemberCount())
				))
				.toList();

		return new GroupGapResponse(
				cohortId,
				GroupGapPolicy.UNDERPERFORMANCE_THRESHOLD_RATIO,
				GroupGapPolicy.LOW_LEVEL_MAX,
				evaluated.size(),
				emptyReason(evaluated, gaps),
				gaps
		);
	}

	/**
	 * 평가가 아예 없는 것과 평가했는데 미달이 0건인 것은 운영 판단이 달라 구분한다.
	 */
	private GroupGapResponse.GroupGapEmptyReason emptyReason(
			List<OperationalActionQueryRepository.GroupGapRow> evaluated,
			List<GroupGapResponse.GroupGapRow> gaps
	) {
		if (!gaps.isEmpty()) {
			return null;
		}
		return evaluated.isEmpty()
				? GroupGapResponse.GroupGapEmptyReason.NO_ELIGIBLE_PARTICIPANT
				: GroupGapResponse.GroupGapEmptyReason.NO_GROUP_UNDERPERFORMANCE;
	}
}
