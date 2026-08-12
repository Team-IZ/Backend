package com.bigproject.backend.domain.assessment.application;

import com.bigproject.backend.domain.assessment.domain.SubmissionMethodPolicy;
import com.bigproject.backend.domain.assessment.domain.TraineeHomeRound;
import com.bigproject.backend.domain.assessment.domain.TraineeHomeRoundRepository;
import com.bigproject.backend.domain.assessment.domain.TraineeMembership;
import com.bigproject.backend.domain.assessment.presentation.dto.AssessmentRoundsResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.CurrentRoundResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.MembershipResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.PastRoundResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.UpcomingRoundResponse;
import com.bigproject.backend.global.security.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 교육생 홈 3구획 조회. 원천은 {@code trainee_home_round_view} 단일 조회다.
 *
 * <p>이 서비스가 하는 일은 구획 분류와 {@code NO_ACTIVE_ROUND} 합성 두 가지뿐이다.
 * 상태 판정은 이미 View가 계약값({@code representative_status} · {@code default_action_code})으로
 * 끝내 두었으므로 여기서 다시 파생시키지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AssessmentRoundQueryService {

	/*
	 * 예정 회차는 제출 마감이 이른 순. PLANNED는 회차 응시 창이 아직 NULL일 수 있어
	 * submission_due_at을 정렬 기준으로 쓴다.
	 */
	private static final Comparator<TraineeHomeRound> UPCOMING_ORDER =
			Comparator.comparing(TraineeHomeRound::submissionDueAt, Comparator.nullsLast(Comparator.naturalOrder()))
					.thenComparing(TraineeHomeRound::roundNo, Comparator.nullsLast(Comparator.naturalOrder()));

	/** 지난 회차는 최근 회차부터. */
	private static final Comparator<TraineeHomeRound> PAST_ORDER =
			Comparator.comparing(TraineeHomeRound::roundNo, Comparator.nullsLast(Comparator.reverseOrder()));

	private final TraineeHomeRoundRepository traineeHomeRoundRepository;
	private final CurrentUserResolver currentUserResolver;

	@Transactional(readOnly = true)
	public AssessmentRoundsResponse getMyAssessmentRounds() {
		UUID traineeUserId = currentUserResolver.resolveCurrentMemberId();
		List<TraineeHomeRound> rounds = traineeHomeRoundRepository.findAllByTraineeUserId(traineeUserId);

		Optional<TraineeHomeRound> current = rounds.stream()
				.filter(TraineeHomeRound::isOpen)
				// 서로 다른 프로젝트의 OPEN 회차가 동시에 열릴 수 있어 가장 급한 것을 고른다.
				.min(UPCOMING_ORDER);

		List<UpcomingRoundResponse> upcoming = rounds.stream()
				.filter(TraineeHomeRound::isPlanned)
				.sorted(UPCOMING_ORDER)
				.map(UpcomingRoundResponse::from)
				.toList();

		List<PastRoundResponse> past = rounds.stream()
				.filter(TraineeHomeRound::isPast)
				.sorted(PAST_ORDER)
				.map(PastRoundResponse::from)
				.toList();

		return new AssessmentRoundsResponse(
				resolveMembership(traineeUserId, rounds, current),
				current
						.map(round -> CurrentRoundResponse.from(round, resolveSubmissionMethods(traineeUserId)))
						.orElseGet(() -> CurrentRoundResponse.noActiveRound(asOfAt(rounds))),
				upcoming,
				past
		);
	}

	/**
	 * 기수·반은 회차 카드가 이미 담고 있으므로 재조회하지 않는다.
	 * 카드가 하나도 없을 때만 {@code cohort_member} + {@code class_membership}로 내려간다.
	 * 기수 미소속이면 전 필드가 null이며, 이것은 오류가 아니라 {@code NO_ACTIVE_ROUND} 응답이다.
	 */
	private MembershipResponse resolveMembership(
			UUID traineeUserId,
			List<TraineeHomeRound> rounds,
			Optional<TraineeHomeRound> current
	) {
		return current
				.or(() -> rounds.stream().findFirst())
				.map(MembershipResponse::from)
				.orElseGet(() -> traineeHomeRoundRepository.findMembershipByUserId(traineeUserId)
						.map(MembershipResponse::from)
						.orElseGet(() -> MembershipResponse.from(TraineeMembership.empty())));
	}

	private List<String> resolveSubmissionMethods(UUID traineeUserId) {
		return traineeHomeRoundRepository.findSubmissionMethodPolicyByUserId(traineeUserId)
				.orElseGet(SubmissionMethodPolicy::defaults)
				.toMethods();
	}

	/** 합성 카드의 조회 시각. 카드가 하나라도 있으면 View가 준 시각을 그대로 쓴다. */
	private static Instant asOfAt(List<TraineeHomeRound> rounds) {
		return rounds.stream()
				.map(TraineeHomeRound::asOfAt)
				.filter(java.util.Objects::nonNull)
				.findFirst()
				.orElseGet(Instant::now);
	}
}
