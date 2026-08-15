package com.bigproject.backend.domain.intervention.application;

import com.bigproject.backend.domain.intervention.domain.RiskOutcomeBatchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 * <p>회차의 <b>마지막 응시 마감 + 1시간 1분</b>이 지나면 그 회차를 한 번에 판정한다. 리포트 발행
 * 이벤트가 아니다 — 발행 시각은 운영자가 미룰 수 있어 판정이 운영 재량에 묶인다.
 *
 * <p>회차가 끝나기를 기다리는 이유는 기수 평균과 백분위다. 판정은 {@code outcome_judged_at}으로
 * 1회성이라 회차 중간에 판정하면 먼저 마친 교육생은 그때까지 완료된 인원만으로 만든 평균과
 * 비교되고, 늦게 마친 교육생은 더 큰 모집단의 평균과 비교된다. 같은 회차 안에서 기준선이 갈리면
 * 안 된다. 앵커를 잡는 식은 {@code JdbcRiskOutcomeBatchRepository.ROUNDS_TO_JUDGE_SQL}에 있다.
 *
 * <p>무효 확인이 진행 중({@code PENDING})인 수행은 확정될 때까지 판정하지 않는다.
 *
 * <h2>트랜잭션 경계</h2>
 *
 * <p>회차 하나가 한 트랜잭션이다. 전부를 한 트랜잭션에 묶으면 한 회차의 실패가 이미 끝난 회차까지
 * 되돌리고, 문장 단위로 쪼개면 판정만 되고 등재가 빠진 회차가 생긴다. 회차 단위면 실패한 회차만
 * 다음 실행에서 다시 잡힌다 — {@code outcome_judged_at}이 NULL로 남기 때문이다.
 *
 * <p>🔴 경계를 {@code @Transactional}이 아니라 {@link TransactionTemplate}으로 잡는다. 회차 하나를
 * 처리하는 메서드를 부르는 것이 <b>같은 빈의 반복문</b>이라, 애너테이션을 달면 자기호출이 되어
 * 프록시를 타지 않는다. 그러면 경계가 조용히 사라지고 문장마다 자동 커밋이 되어 위 문단이 막으려던
 * 상태가 그대로 생긴다 — 판정은 찍혔는데 후보가 없는 회차다. 이때 회차는 판정 큐에서 빠지므로 다음
 * 실행에서 복구되지도 않는다.
 */
@Slf4j
@Service
public class RiskOutcomeBatchService {

	private final RiskOutcomeBatchRepository repository;
	private final TransactionTemplate transactionTemplate;

	public RiskOutcomeBatchService(
			RiskOutcomeBatchRepository repository,
			PlatformTransactionManager transactionManager) {

		this.repository = repository;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

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
	 * <p>순서가 곧 의존이다 — 등재는 {@code outcome_verdict}를 읽는다. 후보와 사유를 두 문장으로 나눈
	 * 이유는 Postgres에서 데이터 수정 CTE가 넣은 행을 같은 문장이 볼 수 없기 때문이다.
	 *
	 * <p>재시험 해소는 여기 없다. 재시험은 판정 <b>뒤에</b> 열리므로 같은 실행에 묶으면 언제나 0건이다.
	 * {@link #resolvePendingRounds()}가 별도 큐로 처리한다.
	 */
	public void runRound(UUID assessmentRoundId) {
		transactionTemplate.executeWithoutResult(status -> {
			int judged = repository.judgeRound(assessmentRoundId, policyVersion);
			int candidates = repository.enrollCandidates(assessmentRoundId);
			int reasons = repository.enrollReasons(assessmentRoundId);
			log.info("회차 유형 판정 완료: assessmentRoundId={}, 판정={}, 신규후보={}, 신규사유={}",
					assessmentRoundId, judged, candidates, reasons);
		});
	}

	/**
	 * 재시험이 새로 끝난 회차의 사유를 해소한다.
	 *
	 * <p>판정과 같은 이유로 회차 하나가 한 트랜잭션이다. 한 회차가 깨져도 나머지는 돌아야 하고, 실패한
	 * 회차는 워터마크가 밀리지 않아 다음 실행에서 다시 잡힌다.
	 *
	 * @return 처리한 회차 수
	 */
	public int resolvePendingRounds() {
		List<UUID> rounds = repository.findRoundsToResolve(roundBatchSize);
		if (rounds.isEmpty()) {
			return 0;
		}
		int processed = 0;
		for (UUID roundId : rounds) {
			try {
				resolveRound(roundId);
				processed++;
			} catch (RuntimeException exception) {
				log.error("재시험 해소 실패. 다음 실행에서 재시도한다: assessmentRoundId={}", roundId, exception);
			}
		}
		return processed;
	}

	/**
	 * 회차 하나의 사유를 재시험 결과와 맞춰 본다.
	 *
	 * <p>두 문장이 <b>같은 트랜잭션</b>이어야 한다. 해소만 커밋되고 표시가 빠지면 회차가 큐에 남아 매
	 * 주기 다시 잡히고, 표시만 커밋되면 아직 안 본 재시험이 확인된 것으로 기록돼 해소를 영영 놓친다.
	 */
	public void resolveRound(UUID assessmentRoundId) {
		transactionTemplate.executeWithoutResult(status -> {
			int resolved = repository.resolveByRetry(assessmentRoundId);
			int evaluated = repository.markResolutionEvaluated(assessmentRoundId);
			log.info("재시험 해소 완료: assessmentRoundId={}, 해소={}, 미해소확인={}",
					assessmentRoundId, resolved, evaluated);
		});
	}
}
