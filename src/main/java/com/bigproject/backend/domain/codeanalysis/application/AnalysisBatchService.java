package com.bigproject.backend.domain.codeanalysis.application;

import com.bigproject.backend.domain.codeanalysis.application.AnalysisServerClient.AnalysisProgress;
import com.bigproject.backend.domain.codeanalysis.application.AnalysisServerClient.AnalysisRequest;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisFailureCode;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJob;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJobStatus;
import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisDispatchRepository;
import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisDispatchRepository.DispatchTarget;
import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisJobRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 마감 후 코드 분석 배치.
 *
 * <p><b>교육생에게 분석 실행 API를 주지 않는다.</b> 실행 주체는 이 배치뿐이다. 근거: 정의서가 트리거를
 * "마감 후 배치"로 규정 · 분석은 팀 단위인데 화면은 개인 단위 · {@code monthly_ai_budget}을 최종
 * 사용자가 쥐면 안 됨 · 마감 전 재제출로 뒤집힐 제출을 분석하면 낭비.
 *
 * <p>지금은 <b>상태 추적까지만</b> 한다. 분석 결과(문제·근거·커밋 이력)를 DB에 넣는 부분은
 * {@code gitHistory} 필드 이름이 AI 회신으로 확정된 뒤에 붙인다.
 */
@Service
@RequiredArgsConstructor
public class AnalysisBatchService {

	private static final Logger log = LoggerFactory.getLogger(AnalysisBatchService.class);

	/** analysis_job.job_type. 값 집합 CHECK가 없어(D-03 보류) 문자열을 여기서 고정한다. */
	private static final String JOB_TYPE_CODE_ANALYSIS = "CODE_ANALYSIS";

	private static final int FIRST_EXECUTION = 1;

	private final AnalysisDispatchRepository dispatchRepository;
	private final AnalysisJobRepository analysisJobRepository;
	private final AnalysisServerClient analysisServerClient;

	/**
	 * 마감된 회차의 현재 제출을 팀당 1건씩 분석 요청한다.
	 *
	 * <p>제출 1건마다 트랜잭션을 나눈다. 한 팀의 요청이 실패해도 나머지가 함께 롤백되면 안 되기
	 * 때문이다 — 마감 직후 한 번에 도는 배치라 실패 하나가 회차 전체를 막으면 피해가 크다.
	 *
	 * @return 요청을 보낸 제출 수
	 */
	public int dispatchDueSubmissions(Instant now) {
		List<DispatchTarget> targets = dispatchRepository.findDueSubmissions(now);
		int dispatched = 0;
		for (DispatchTarget target : targets) {
			try {
				dispatchOne(target, now);
				dispatched++;
			} catch (RuntimeException exception) {
				// 여기서 삼키는 이유는 다음 팀을 계속 처리하기 위해서다. 실패한 제출은 job 행이
				// FAILED 로 남거나(아래 dispatchOne) 아예 안 남아 다음 배치가 다시 고른다.
				log.error("분석 요청 실패: submissionId={}", target.getSubmissionId(), exception);
			}
		}
		return dispatched;
	}

	/**
	 * 제출 1건을 요청한다.
	 *
	 * <p>순서가 중요하다. <b>job 행을 먼저 QUEUED로 저장하고</b> AI를 부른다. 반대로 하면 202를 받고도
	 * 행이 없는 순간이 생기고, 그 사이에 프로세스가 죽으면 AI에는 실행이 있는데 우리 원장에는 없다.
	 */
	@Transactional
	protected void dispatchOne(DispatchTarget target, Instant now) {
		String traceId = UUID.randomUUID().toString();
		AnalysisJob job = analysisJobRepository.save(AnalysisJob.queued(
				target.getOrgId(),
				target.getAssessmentRoundId(),
				target.getTeamId(),
				target.getSubmissionId(),
				batchKeyOf(target),
				JOB_TYPE_CODE_ANALYSIS,
				FIRST_EXECUTION,
				traceId
		));

		try {
			UUID externalJobId = analysisServerClient.requestAnalysis(new AnalysisRequest(
					target.getSubmissionId(),
					null,
					target.getRepositoryUrl(),
					target.getRequestedBranch(),
					"TOTAL",
					null,
					3,
					null,
					null,
					idempotencyKeyOf(target.getSubmissionId(), job.getExecutionNo()),
					traceId
			));
			job.acceptExternalJob(externalJobId);
		} catch (AnalysisServerException exception) {
			// 요청이 거절되면 그 자체가 분석 실패다. QUEUED로 남겨 두면 폴링이 영원히 붙들고 있는데,
			// external_job_id 가 없어 조회할 대상조차 없다.
			job.markFailed(exception.failureCode(), exception.getMessage(), now, now);
			throw exception;
		}
	}

	/**
	 * 진행 중인 실행의 상태를 갱신한다.
	 *
	 * <p>AI 서버가 job을 메모리에만 두어 재시작하면 404가 난다(스펙 명시). 그건 오류가 아니라 재요청
	 * 신호이므로 실패로 기록하지 않고 {@code external_job_id}를 지워 다음 배치가 다시 보내게 한다.
	 *
	 * @return 상태가 바뀐 실행 수
	 */
	@Transactional
	public int pollActiveJobs() {
		List<AnalysisJob> active = analysisJobRepository.findByStatusIn(
				List.of(AnalysisJobStatus.QUEUED, AnalysisJobStatus.RUNNING));
		int updated = 0;
		for (AnalysisJob job : active) {
			if (job.getExternalJobId() == null) {
				continue;
			}
			if (applyProgress(job)) {
				updated++;
			}
		}
		return updated;
	}

	private boolean applyProgress(AnalysisJob job) {
		AnalysisProgress progress = analysisServerClient.fetchProgress(job.getExternalJobId()).orElse(null);
		if (progress == null) {
			// AI 서버가 모르는 job이다. 재시작으로 유실됐다는 뜻이라 같은 execution_no로 다시 보낸다.
			log.warn("AI 서버가 모르는 작업이다. 재요청 대상으로 되돌린다: jobId={}, externalJobId={}",
					job.getJobId(), job.getExternalJobId());
			job.acceptExternalJob(null);
			return true;
		}
		return switch (progress.status()) {
			case QUEUED -> false;
			case RUNNING -> {
				if (job.getStatus() == AnalysisJobStatus.RUNNING) {
					yield false;
				}
				job.markRunning(progress.startedAt() == null ? Instant.now() : progress.startedAt());
				yield true;
			}
			case SUCCEEDED, PARTIAL -> {
				job.markCompleted(progress.status(), progress.startedAt(),
						progress.completedAt() == null ? Instant.now() : progress.completedAt());
				yield true;
			}
			case FAILED -> {
				job.markFailed(
						// 값 집합 밖의 코드가 오면 저장이 CHECK 로 막힌다. 실패를 기록조차 못 하는
						// 것보다 MODEL_ERROR 로 남기고 원문을 사유에 붙이는 편이 낫다.
						progress.failureCode() == null ? AnalysisFailureCode.MODEL_ERROR : progress.failureCode(),
						progress.failureReason() == null || progress.failureReason().isBlank()
								? "AI 서버가 실패 사유를 주지 않았다." : progress.failureReason(),
						progress.startedAt(),
						progress.completedAt() == null ? Instant.now() : progress.completedAt());
				yield true;
			}
		};
	}

	/**
	 * {@code analysis_job.batch_key}. 정의서: "assessment_round_id·team_id·submission_id와 실행 목적을
	 * 안정적으로 포함한다."
	 *
	 * <p>{@code uq_analysis_job_active(batch_key, job_type)}가 이 값으로 동시 실행을 막으므로, 같은
	 * 제출에 대해 항상 같은 문자열이 나와야 한다.
	 */
	private String batchKeyOf(DispatchTarget target) {
		return "%s:%s:%s:%s".formatted(
				target.getAssessmentRoundId(), target.getTeamId(),
				target.getSubmissionId(), JOB_TYPE_CODE_ANALYSIS);
	}

	/** AI 서버 계약: {@code submissionId:attemptNo}. attemptNo는 우리 execution_no다. */
	private String idempotencyKeyOf(UUID submissionId, int executionNo) {
		return submissionId + ":" + executionNo;
	}
}
