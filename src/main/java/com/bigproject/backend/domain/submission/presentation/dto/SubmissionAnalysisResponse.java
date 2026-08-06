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
						저장소 주소 오류도 제출이 아니라 여기로 드러난다.""",
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
				job.getFailureCode(),
				job.getFailureReason(),
				job.getAnalysisId()
		);
	}
}
