package com.bigproject.backend.domain.intervention.application;

import com.bigproject.backend.domain.intervention.domain.RiskOutcomeBatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 회차 유형 판정과 면담 후보 등재를 실행한다.
 *
 * <h2>이 배치가 없으면</h2>
 *
 * <p>면담 목록(MG-03)은 위험 판정이 만든 큐라 등재가 없으면 영구히 빈 화면이다. 지금까지 유일하게
 * 채워지던 경로는 무효 확인 API의 INVALID_ATTEMPT 하나였고, 나머지 4종은 생산자가 없었다.
 *
 * <h2>트리거 (정책 §5B)</h2>
 *
 * <p>"위험 발생은 INITIAL 확정 시"다. 리포트 발행 이벤트가 아니다 — 리포트를 기다리면 발행이 늦은
 * 회차의 면담이 통째로 밀린다. 여기서 "확정"은 {@code measurement_attempt.status='COMPLETED'}이며,
 * 무효 확인이 진행 중({@code PENDING})인 수행은 확정될 때까지 판정하지 않는다.
 *
 * <h2>트랜잭션 경계</h2>
 *
 * <p>회차 하나가 한 트랜잭션이다. 전부를 한 트랜잭션에 묶으면 한 회차의 실패가 이미 끝난 회차까지
 * 되돌리고, 문장 단위로 쪼개면 판정만 되고 등재가 빠진 회차가 생긴다. 회차 단위면 실패한 회차만
 * 다음 실행에서 다시 잡힌다 — {@code outcome_judged_at}이 NULL로 남기 때문이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskOutcomeBatchService {

	private final RiskOutcomeBatchRepository repository;

	/**
	 * 적용 정책 버전.
	 *
	 * <p>판정식을 바꾸면 이 값을 올린다. {@code interview_candidate_reason.policy_version}이
	 * {@code INTEGER NOT NULL}인 것이 "판정 규칙에 버전이 있다"는 전제였고, 이 값이 그 1번이다.
	 */
	@Value("${intervention.risk-outcome.policy-version:1}")
	private int policyVersion;

	/** 한 번 실행에서 처리할 회차 수 상한. 첫 실행에서 밀린 회차가 한꺼번에 잡히는 것을 막는다. */
	@Value("${intervention.risk-outcome.round-batch-size:20}")
	private int roundBatchSize;

	/**
	 * 판정되지 않은 회차를 찾아 판정·등재·해소를 수행한다.
	 *
	 * @return 처리한 회차 수
	 */
	public int runPendingRounds() {
		List<UUID> rounds = repository.findRoundsToJudge(roundBatchSize);
		if (rounds.isEmpty()) {
			return 0;
		}
		int processed = 0;
		for (UUID roundId : rounds) {
			try {
				runRound(roundId);
				processed++;
			} catch (RuntimeException exception) {
				// 한 회차가 깨져도 나머지는 돌린다. 실패한 회차는 outcome_judged_at 이 NULL 로
				// 남아 다음 실행에서 다시 잡힌다.
				log.error("회차 유형 판정 실패. 다음 실행에서 재시도한다: assessmentRoundId={}", roundId, exception);
			}
		}
		return processed;
	}

	/**
	 * 회차 하나를 판정하고 후보를 등재한다.
	 *
	 * <p>순서가 곧 의존이다 — 등재는 {@code outcome_verdict}를 읽고, 해소는 등재된 사유를 읽는다.
	 * 후보와 사유를 두 문장으로 나눈 이유는 Postgres에서 데이터 수정 CTE가 넣은 행을 같은 문장이
	 * 볼 수 없기 때문이다.
	 */
	@Transactional
	public void runRound(UUID assessmentRoundId) {
		int judged = repository.judgeRound(assessmentRoundId, policyVersion);
		int candidates = repository.enrollCandidates(assessmentRoundId);
		int reasons = repository.enrollReasons(assessmentRoundId);
		int resolved = repository.resolveByRetry(assessmentRoundId);
		log.info("회차 유형 판정 완료: assessmentRoundId={}, 판정={}, 신규후보={}, 신규사유={}, 재시험해소={}",
				assessmentRoundId, judged, candidates, reasons, resolved);
	}
}
