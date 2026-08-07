package com.bigproject.backend.domain.codeanalysis.application;

import com.bigproject.backend.domain.codeanalysis.domain.AnalysisFailureCode;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJobStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * AI 서버의 코드 분석 API를 부르는 포트.
 *
 * <p>인터페이스로 둔 이유는 <b>AI 서버 없이 배치를 검증하기 위해서다.</b> 상태 전이·멱등·404 보정은
 * 전부 우리 쪽 로직인데, 실제 서버에 붙여야만 테스트할 수 있다면 서버가 내려간 동안 아무것도 확인할 수
 * 없다.
 *
 * <p><b>분석 결과(문제·근거·커밋 이력)는 여기서 다루지 않는다.</b> {@code gitHistory}의 필드 이름이
 * 아직 우리 제안일 뿐이고 AI 회신으로 확정되지 않았다. 확정 전에 DTO를 만들면 이름이 다를 때 매핑을
 * 통째로 고쳐야 하므로, 지금은 <b>상태 추적에 필요한 만큼만</b> 정의한다.
 */
public interface AnalysisServerClient {

	/**
	 * 분석을 요청하고 AI가 202로 준 작업 ID를 받는다.
	 *
	 * @throws AnalysisServerException 요청이 거절됐거나 응답을 해석할 수 없을 때
	 */
	UUID requestAnalysis(AnalysisRequest request);

	/**
	 * 진행 상태를 조회한다.
	 *
	 * <p>결과가 비어 있으면 AI 서버가 그 작업을 모른다는 뜻이다(404). 스펙에 "job storage is
	 * in-memory; process restart causes 404"라고 명시돼 있어 <b>정상 동작 중에도 일어난다.</b>
	 * 호출부는 이를 오류가 아니라 재요청 신호로 다뤄야 한다.
	 */
	Optional<AnalysisProgress> fetchProgress(UUID externalJobId);

	/**
	 * 분석 요청 본문. 전 필드를 우리 DB에서 채울 수 있음을 확인했다.
	 *
	 * <p>{@code repositoryUrl}은 팀의 ACTIVE {@code repository} 행에서, {@code requestedBranch}는
	 * 제출별 {@code submission.requested_branch}에서 온다. 브랜치가 {@code null}이면 AI가 기본
	 * 브랜치를 고르고 {@code resolvedBranch}로 알려준다.
	 */
	record AnalysisRequest(
			UUID submissionId,
			UUID attemptId,
			String repositoryUrl,
			String requestedBranch,
			String extractionScope,
			String commitEmail,
			int questionBudget,
			UUID curriculumVersionId,
			String providerModelCode,
			String idempotencyKey,
			String traceId
	) {
	}

	/**
	 * 폴링 응답 중 상태 추적에 쓰는 부분.
	 *
	 * @param failureCode {@code status=FAILED}일 때만 값이 있다. 값 집합 밖의 문자열이 오면
	 *                    {@link AnalysisFailureCode#parse}가 비워서 넘기므로, 호출부가 "알 수 없는
	 *                    실패"를 어떻게 기록할지 정한다.
	 */
	record AnalysisProgress(
			AnalysisJobStatus status,
			Instant startedAt,
			Instant completedAt,
			AnalysisFailureCode failureCode,
			String failureReason
	) {
	}
}
