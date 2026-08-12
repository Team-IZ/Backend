package com.bigproject.backend.domain.submission.presentation.dto;

import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJob;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "코드 분석 진행 상태와 실패 사유")
public record SubmissionAnalysisResponse(

		UUID submissionId,

		@Schema(
				description = """
						NOT_STARTED는 분석 배치가 아직 이 제출을 집어가지 않은 상태다. 분석 실행은 회차 마감 후에
						시작하므로 마감 전에는 정상적으로 NOT_STARTED가 반환된다.""",
				example = "QUEUED"
		)
		SubmissionAnalysisPhase phase,

		@Schema(description = "분석이 시작되지 않았으면 null이다.")
		UUID analysisJobId,

		@Schema(description = "재시도 회차. 분석이 시작되지 않았으면 null이다.")
		Integer executionNo,

		Instant startedAt,

		Instant completedAt,

		@Schema(
				description = """
						FAILED일 때만 값이 있다. 분석 실행 실패 6종과 저장소 접근 실패 5종을 합한 11종이다 —
						저장소 주소 오류도 제출이 아니라 여기로 드러난다.

						SESSION_PREPARATION_FAILED만 예외로 analysis_job.failure_code에 없는 값이다.
						분석은 성공했지만 이 교육생의 세션·문항이 준비되지 않아 응시를 시작할 수 없다는
						뜻이며, 서버가 조회 시점에 판정해 내려 준다.""",
				example = "REPO_NOT_FOUND"
		)
		String failureCode,

		String failureReason,

		@Schema(description = "분석 성공 시 생성된 코드 분석 결과. 그 외에는 null이다.")
		UUID codeAnalysisId
) {

	/** 분석 배치가 아직 이 제출을 집어가지 않았다. */
	public static SubmissionAnalysisResponse notStarted(UUID submissionId) {
		return new SubmissionAnalysisResponse(
				submissionId, SubmissionAnalysisPhase.NOT_STARTED, null, null, null, null, null, null, null
		);
	}

	public static SubmissionAnalysisResponse of(AnalysisJob job) {
		return new SubmissionAnalysisResponse(
				job.getSubmissionId(),
				SubmissionAnalysisPhase.valueOf(job.getStatus().name()),
				job.getJobId(),
				job.getExecutionNo(),
				job.getStartedAt(),
				job.getCompletedAt(),
				job.getFailureCode() == null ? null : job.getFailureCode().name(),
				job.getFailureReason(),
				job.getAnalysisId()
		);
	}

	/**
	 * 분석은 성공했지만 이 교육생의 세션·문항이 준비되지 않았다.
	 *
	 * <p><b>원장은 그대로 두고 응답만 바꾼다.</b> {@code analysis_job}은 SUCCEEDED로 남는다 — AI
	 * 분석은 실제로 성공했고 비용도 나갔으며, FAILED로 쓰면 재시도 대상이 되어 같은 분석을 또 돌린다.
	 * 하지만 교육생이 볼 수 있는 사실은 "응시를 시작할 수 없다"이므로 화면에는 실패로 내려간다.
	 *
	 * <p>{@code analysisJobId}·{@code executionNo}·시각은 그대로 실어 준다. 운영이 로그와 원장을
	 * 대조할 때 필요한 값이고, 이 실패가 어느 실행에서 났는지가 그 값으로만 특정된다.
	 *
	 * <p>{@code codeAnalysisId}는 비운다. 값을 남기면 화면이 결과 조회로 넘어갈 수 있다고 오해하는데,
	 * 실제로는 세션이 없어 응시를 시작할 수 없다.
	 */
	public static SubmissionAnalysisResponse sessionPreparationFailed(AnalysisJob job) {
		return new SubmissionAnalysisResponse(
				job.getSubmissionId(),
				SubmissionAnalysisPhase.FAILED,
				job.getJobId(),
				job.getExecutionNo(),
				job.getStartedAt(),
				job.getCompletedAt(),
				"SESSION_PREPARATION_FAILED",
				"분석은 완료됐지만 응시할 문항이 준비되지 않았습니다. 담당 매니저에게 문의해 주세요.",
				null
		);
	}
}
