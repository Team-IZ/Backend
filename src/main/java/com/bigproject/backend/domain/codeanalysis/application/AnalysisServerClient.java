package com.bigproject.backend.domain.codeanalysis.application;

import com.bigproject.backend.domain.codeanalysis.domain.AnalysisFailureCode;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJobStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * AI 서버의 코드 분석 API를 부르는 포트.
 *
 * <p>인터페이스로 둔 이유는 <b>AI 서버 없이 배치를 검증하기 위해서다.</b> 상태 전이·멱등·404 보정은
 * 전부 우리 쪽 로직인데, 실제 서버에 붙여야만 테스트할 수 있다면 서버가 내려간 동안 아무것도 확인할 수
 * 없다.
 *
 * <p>분석 결과는 {@link AnalysisResultPayload}로 함께 받는다(2026-08-09). 종전에는 {@code gitHistory}
 * 필드 이름이 확정되지 않아 상태 추적만 다뤘으나, 스펙이 {@code commitHash}·{@code commitMessage}로
 * 확정돼 매핑을 붙였다.
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
	 *
	 * @param method            {@code GITHUB_URL} 또는 {@code ZIP_WITH_GITLOG}. AI 요청 본문에서
	 *                          <b>유일한 필수 필드</b>다.
	 * @param problemScope      {@code TEAM_SHARED_PROBLEM} 또는 {@code INDIVIDUAL_OWN_COMMIT}. AI가
	 *                          이 값과 {@code teaches}/{@code focusItems}의 존재 여부를 상호 배타로
	 *                          검증한다(2026-08-10 AI팀 확인) — {@code TEAM_SHARED_PROBLEM}인데
	 *                          {@code teaches}가 없으면, {@code INDIVIDUAL_OWN_COMMIT}인데
	 *                          {@code teaches}가 있으면 거부한다. 지금은 {@code TEAM_SHARED_PROBLEM}만
	 *                          지원한다.
	 * @param providerModelCode 🔴 <b>반드시 채운다.</b> 생략하면 AI가 자기 기본 모델을 쓰는데, 그 모델의
	 *                          {@code modelCode}가 응답 {@code aiUsage[]}에 실려 돌아온다.
	 *                          {@code ai_usage.model_code}는 {@code ai_model}을 참조하는 FK라
	 *                          우리 카탈로그에 없는 모델이면 사용량 행이 통째로 적재 실패한다.
	 *                          🔴 <b>2026-08-10 정정:</b> 필드 이름과 달리 값은
	 *                          {@code ai_model.provider_model_code}가 아니라 <b>전체 코드
	 *                          {@code ai_model.model_code}</b>다({@code provider || '/' ||
	 *                          provider_model_code} 형태, 예: {@code nvidia/nemotron-3-ultra-550b-a55b}).
	 *                          이걸 보내야 AI가 모델을 인식한다 — 공급자 원본 식별자만 보내면
	 *                          AI가 모델을 못 찾는다.
	 * @param requirements      {@code project_requirement}(프로젝트 스코프, {@code active=TRUE}).
	 *                          2026-08-10 확인: {@code GITHUB_URL}일 때만 보낸다. ZIP 제출은 null.
	 * @param focusItems        {@code TEAM_SHARED_PROBLEM}에서는 <b>항상 null.</b> teaches가 이미
	 *                          "무엇을 물을지"를 정하므로 기준이 둘이 되는 걸 피한다(2026-08-10 결정).
	 *                          {@code INDIVIDUAL_OWN_COMMIT}(P5, 미구현)에서 AI가 코드 분석 후 출제
	 *                          방향을 잡을 때 참고할 선택 항목이다.
	 * @param teaches           {@code TEAM_SHARED_PROBLEM}에서 <b>필수, 비면 안 된다</b> — AI가 거부한다.
	 *                          이 회차에 적용되는 검증 개념 집합
	 *                          ({@code project_assessment_round.concept_set_id}, 없으면 프로젝트의
	 *                          ACTIVE {@code project_verification_concept_set})의 {@code teaches} +
	 *                          {@code curriculum_teaches_mapping}. {@code INDIVIDUAL_OWN_COMMIT}에서는
	 *                          <b>항상 null</b>이어야 한다(있으면 AI가 거부).
	 */
	record AnalysisRequest(
			String method,
			UUID submissionId,
			String repositoryUrl,
			String requestedBranch,
			String problemScope,
			String extractionScope,
			String commitEmail,
			int questionBudget,
			List<RequirementItem> requirements,
			List<FocusItem> focusItems,
			List<TeachItem> teaches,
			String providerModelCode,
			String artifactStorageUri,
			String artifactFileName,
			String idempotencyKey,
			String traceId
	) {

		/**
		 * ZIP 본체를 함께 보내야 하는 요청인가.
		 *
		 * <p>{@code method}만 보지 않고 저장 위치까지 확인한다. ZIP 제출인데 artifact 행이 없으면
		 * 보낼 파일이 없는 것이고, 그 상태로 multipart를 만들면 빈 파트가 나간다 —
		 * 그러면 AI가 {@code ARCHIVE_INVALID}로 답해 원인이 "파일이 깨졌다"로 잘못 기록된다.
		 */
		public boolean requiresArtifactUpload() {
			return "ZIP_WITH_GITLOG".equals(method) && artifactStorageUri != null;
		}
	}

	/** {@code project_requirement.requirement_id}/{@code .title}. */
	record RequirementItem(String requirementId, String text) {
	}

	/** {@code question_focus_item.question_focus_item_id}/{@code .name}/{@code .description}. */
	record FocusItem(String focusItemId, String name, String description) {
	}

	/**
	 * {@code teaches} 한 건. {@code id}·{@code label}은 {@code teaches} 테이블(canonical) 값이고,
	 * {@code unitId}·{@code sourcePages}는 이 회차의 {@code curriculum_teaches_mapping}(source,
	 * 프로젝트별 교안 인스턴스) 값이다.
	 *
	 * <p>{@code unitId}는 리포트 생성 요청이 이미 쓰는 것과 같은 컨벤션이다
	 * ({@code JdbcReportPayloadRepository.findTeaches}: {@code unitId = section_id}).
	 *
	 * <p>2026-08-10 확인된 AI 스키마 예시가 {@code kind}·{@code evidence}·{@code siblingNames}를
	 * 쓰지 않아 뺐다 — 매핑에서 값 자체는 여전히 구할 수 있으니, AI가 나중에 필요하다면 다시 붙이면 된다.
	 */
	record TeachItem(
			String id,
			String label,
			String unitId,
			List<Integer> sourcePages
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
			String failureReason,
			AnalysisResultPayload result,
			java.util.List<AiUsageEntry> aiUsage
	) {

		/**
		 * 적재할 결과가 실려 있는가.
		 *
		 * <p>PARTIAL 도 결과를 준다 — 일부 개념만 문항을 만든 경우이고, 만들어진 것은 저장해야 한다.
		 * 반대로 SUCCEEDED 인데 {@code result} 가 비어 오는 것은 계약 위반이라 적재를 건너뛰고
		 * 상태만 옮긴다(호출부가 로그를 남긴다).
		 */
		public boolean hasResult() {
			return result != null
					&& (status == AnalysisJobStatus.SUCCEEDED || status == AnalysisJobStatus.PARTIAL);
		}
	}
}
