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

		@Schema(description = "분석이 시작되지 않았으면 null이다.", nullable = true)
		UUID analysisJobId,

		@Schema(description = "재시도 회차. 분석이 시작되지 않았으면 null이다.", nullable = true)
		Integer executionNo,

		Instant startedAt,

		Instant completedAt,

		@Schema(
				description = """
						FAILED일 때만 값이 있다. 분석 실행 실패 6종과 저장소 접근 실패 5종을 합한 11종이다 —
						저장소 주소 오류도 제출이 아니라 여기로 드러난다.

						SESSION_PREPARATION_FAILED와 EXTERNAL_JOB_ID_LOST는 analysis_job.failure_code에 없는
						값이다. 둘 다 서버가 조회 시점에 판정해 내려 준다 — 전자는 분석은 성공했지만 이 교육생의
						세션·문항이 준비되지 않은 경우, 후자는 활성 분석 행이 AI 작업 ID를 잃어 상태를 더 따라갈
						수 없는 경우다.""",
				example = "REPO_NOT_FOUND"
		)
		String failureCode,

		String failureReason,

		@Schema(description = "분석 성공 시 생성된 코드 분석 결과. 그 외에는 null이다.", nullable = true)
		UUID codeAnalysisId
) {

	/**
	 * 활성 job이 AI 작업 ID를 잃었을 때의 실패 코드. {@code analysis_job.failure_code}에는 없는 값이다.
	 *
	 * <p>폴러가 곧 이 job을 {@code MODEL_ERROR}로 닫지만(최대 1분), 그 사이에도 화면은 같은 사실을
	 * 봐야 한다. 원장의 코드를 미리 흉내 내지 않고 별도 값으로 두는 이유는, 이 응답이 원장에 아직
	 * 쓰이지 않은 <b>조회 시점 판정</b>이라는 점이 {@code SESSION_PREPARATION_FAILED}와 같기 때문이다.
	 */
	public static final String EXTERNAL_JOB_ID_LOST = "EXTERNAL_JOB_ID_LOST";

	/**
	 * 같은 사실을 TR-02 제출 현황(
	 * {@link com.bigproject.backend.domain.submission.application.MySubmissionService})도 보여 준다.
	 * 두 화면의 문구가 갈라지지 않게 여기서 한 번만 적는다.
	 */
	public static final String EXTERNAL_JOB_ID_LOST_MESSAGE =
			"분석 서버의 작업 정보가 유실되어 분석을 계속할 수 없습니다. "
					+ "코드를 다시 제출해 분석을 재시도해 주세요. 다시 제출할 수 없다면 담당 매니저에게 문의해 주세요.";

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

	/**
	 * 활성 job인데 AI가 발급한 작업 ID가 없다. 상태를 더 따라갈 수 없으므로 실패로 내려 준다.
	 *
	 * <p><b>500이 아니라 FAILED다(2026-08-13).</b> 종전에는 서버 데이터 오류로 던졌는데, 폴링이 초
	 * 단위로 도는 화면에서 그 선택은 오류 응답만 반복해서 쌓고 교육생에게는 아무것도 알려주지 못했다.
	 * 게다가 같은 사실을 TR-02 제출 현황은 이미 실패로 보여 주고 있어서, 목록은 "분석 실패"인데 상세는
	 * 500인 상태가 됐다. 두 화면이 같은 답을 하도록 맞춘다.
	 *
	 * <p>원장은 건드리지 않는다. 폴러가 다음 회차(최대 1분)에 이 job을 {@code MODEL_ERROR}로 닫고,
	 * 재시도 여지가 남아 있으면 안전망이 다시 요청한다 — 그때는 이 응답 대신 새 실행의 상태가 나간다.
	 */
	public static SubmissionAnalysisResponse externalJobIdLost(AnalysisJob job) {
		return new SubmissionAnalysisResponse(
				job.getSubmissionId(),
				SubmissionAnalysisPhase.FAILED,
				job.getJobId(),
				job.getExecutionNo(),
				job.getStartedAt(),
				job.getCompletedAt(),
				EXTERNAL_JOB_ID_LOST,
				EXTERNAL_JOB_ID_LOST_MESSAGE,
				null
		);
	}
}
