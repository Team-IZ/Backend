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
 *
 * <h2>🔴 회차는 하나도 잃지 않는다</h2>
 *
 * <p>구획 분류의 불변식은 <b>"조회된 회차 = current + upcoming + past"</b>다. 종전에는
 * {@code current}를 OPEN 중 하나만 고르고 {@code upcoming}은 {@code PLANNED}만,
 * {@code past}는 {@code CLOSED}·{@code COMPLETED}만 받아서, <b>OPEN 회차가 둘 이상이면
 * 뽑히지 못한 쪽이 응답에서 통째로 사라졌다</b>(24차 R4 — 학생이 제출해야 할 6차를
 * 홈 어디에서도 볼 수 없었다).
 *
 * <p>그래서 current로 뽑히지 않은 OPEN 회차를 <b>제출 마감 기준</b>으로 나눠 담는다.
 * 마감이 남았으면 {@code upcoming}(아직 기회가 있다), 지났으면 {@code past}(더는 낼 수 없다)다.
 * {@code UpcomingRoundResponse.roundStatus}에 {@code OPEN}이 실릴 수 있는 것은 이 때문이다.
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
					.thenComparing(TraineeHomeRound::projectSequenceNo, Comparator.nullsLast(Comparator.naturalOrder()));

	/*
	 * 지난 회차는 최근 회차부터.
	 *
	 * 🔴 정렬 축은 projectSequenceNo다 — roundNo가 아니다. roundNo는 프로젝트 안에서만 유일해
	 * 미니프로젝트에서는 전부 1이고, 그 값으로 정렬하면 비교가 전부 무승부라 안정 정렬이
	 * DB가 준 순서를 그대로 통과시킨다. 실제로 `1차 · 4차 · 3차 · 2차`로 나갔다(24차 R5).
	 */
	private static final Comparator<TraineeHomeRound> PAST_ORDER =
			Comparator.comparing(TraineeHomeRound::projectSequenceNo, Comparator.nullsLast(Comparator.reverseOrder()))
					.thenComparing(TraineeHomeRound::submissionDueAt, Comparator.nullsLast(Comparator.reverseOrder()));

	private final TraineeHomeRoundRepository traineeHomeRoundRepository;
	private final CurrentUserResolver currentUserResolver;

	@Transactional(readOnly = true)
	public AssessmentRoundsResponse getMyAssessmentRounds() {
		UUID traineeUserId = currentUserResolver.resolveCurrentMemberId();
		List<TraineeHomeRound> rounds = traineeHomeRoundRepository.findAllByTraineeUserId(traineeUserId);
		Instant asOfAt = asOfAt(rounds);

		Optional<TraineeHomeRound> current = pickCurrent(rounds, asOfAt);
		UUID currentRoundId = current.map(TraineeHomeRound::assessmentRoundId).orElse(null);

		List<UpcomingRoundResponse> upcoming = rounds.stream()
				.filter(round -> !round.assessmentRoundId().equals(currentRoundId))
				.filter(round -> round.isPlanned() || (round.isOpen() && !round.isSubmissionClosedAt(asOfAt)))
				.sorted(UPCOMING_ORDER)
				.map(UpcomingRoundResponse::from)
				.toList();

		List<PastRoundResponse> past = rounds.stream()
				.filter(round -> !round.assessmentRoundId().equals(currentRoundId))
				.filter(round -> round.isPast() || (round.isOpen() && round.isSubmissionClosedAt(asOfAt)))
				.sorted(PAST_ORDER)
				.map(PastRoundResponse::from)
				.toList();

		return new AssessmentRoundsResponse(
				resolveMembership(traineeUserId, rounds, current),
				current
						.map(round -> CurrentRoundResponse.from(round, resolveSubmissionMethods(traineeUserId)))
						.orElseGet(() -> CurrentRoundResponse.noActiveRound(asOfAt)),
				upcoming,
				past
		);
	}

	/**
	 * 지금 할 일인 회차 하나.
	 *
	 * <h2>🔴 마감이 지난 OPEN 회차를 "지금 할 일"로 고르지 않는다</h2>
	 *
	 * <p>종전에는 OPEN 회차 중 {@code submission_due_at}이 가장 이른 것을 골랐다. 그런데 회차가
	 * OPEN인 채로 제출 마감만 지나는 구간이 있어, <b>이미 마감된 회차가 계속 "가장 급한 것"으로
	 * 뽑혔다.</b> 실제로 마감이 지난 5차가 current를 차지하고 마감이 남은 6차가 밀려났다(24차 R4).
	 *
	 * <p>그래서 <b>마감이 남은 것을 먼저 보고</b>, 그 안에서 가장 급한 것을 고른다. 전부 마감이
	 * 지났으면 그 중 가장 늦게 마감한 회차를 고른다 — 회차가 OPEN인 이상 응시·리포트 대기 같은
	 * 할 일이 남아 있을 수 있고, 그때는 가장 최근 회차가 학생이 보고 싶은 것이다.
	 */
	private static Optional<TraineeHomeRound> pickCurrent(List<TraineeHomeRound> rounds, Instant asOfAt) {
		List<TraineeHomeRound> openRounds = rounds.stream().filter(TraineeHomeRound::isOpen).toList();
		return openRounds.stream()
				.filter(round -> !round.isSubmissionClosedAt(asOfAt))
				.min(UPCOMING_ORDER)
				.or(() -> openRounds.stream().min(PAST_ORDER));
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

	/**
	 * 구획 분류와 합성 카드가 함께 쓰는 기준 시각. 카드가 하나라도 있으면 View가 준
	 * {@code as_of_at}(= DB의 {@code CURRENT_TIMESTAMP})을 그대로 쓴다 — View가 이미 그 시각으로
	 * {@code can_submit} · {@code representative_status}를 판정했으므로 서버 시계로 다시 재면
	 * 같은 응답 안에서 두 값이 어긋난다.
	 */
	private static Instant asOfAt(List<TraineeHomeRound> rounds) {
		return rounds.stream()
				.map(TraineeHomeRound::asOfAt)
				.filter(java.util.Objects::nonNull)
				.findFirst()
				.orElseGet(Instant::now);
	}
}
