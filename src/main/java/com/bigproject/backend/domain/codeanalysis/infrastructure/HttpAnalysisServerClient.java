package com.bigproject.backend.domain.codeanalysis.infrastructure;

import com.bigproject.backend.domain.codeanalysis.application.AnalysisServerClient;
import com.bigproject.backend.domain.codeanalysis.application.AiUsageEntry;
import com.bigproject.backend.domain.codeanalysis.application.AnalysisResultPayload;
import com.bigproject.backend.domain.codeanalysis.application.AnalysisServerException;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisFailureCode;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJobStatus;
import com.bigproject.backend.domain.submission.application.SubmissionArtifactStorage;
import com.bigproject.backend.domain.submission.domain.SubmissionException;
import com.bigproject.backend.global.ai.AiCallException;
import com.bigproject.backend.global.ai.AiClient;
import com.bigproject.backend.global.ai.AiClientConfig;
import com.bigproject.backend.global.ai.AiProxyWarmUp;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link AnalysisServerClient}의 실제 구현. AI(FastAPI)의 {@code POST /analyses}·
 * {@code GET /analyses/{jobId}}를 부른다.
 *
 * <p>이 빈이 등록되면 {@link AnalysisServerClientConfig}의 "항상 실패하는" 폴백이
 * {@code @ConditionalOnMissingBean}에 의해 물러난다.
 *
 * <p><b>대상은 원본 서버({@code ai.origin-base-url})다</b>(2026-08-11). 코드 제출 분석은 프록시를
 * 거치지 않는다. 경로에는 {@link AiClient#API_V0}를 직접 붙인다 — base-url에는 호스트만 있다.
 *
 * <h2>실패를 우리 값 집합으로 접는다</h2>
 *
 * <p>{@link AiClient}는 모든 실패를 {@link AiCallException} 하나로 주는데, 그 {@code failureCode}는
 * AI 명세 §5.2의 값(TIMEOUT·PROVIDER_ERROR 등)이라 {@code ck_analysis_job_failure_code_2}의 12종과
 * 다르다. 그대로 저장하면 CHECK 위반으로 <b>실패를 기록조차 못 하게</b> 되므로 여기서 변환한다.
 */
@Slf4j
@Component
public class HttpAnalysisServerClient implements AnalysisServerClient {

	private static final String ANALYSES_PATH = AiClient.API_V0 + "/analyses";

	private final AiClient aiClient;
	private final SubmissionArtifactStorage artifactStorage;
	private final AiProxyWarmUp proxyWarmUp;

	public HttpAnalysisServerClient(
			@Qualifier(AiClientConfig.AI_ORIGIN_CLIENT) AiClient aiClient,
			SubmissionArtifactStorage artifactStorage,
			AiProxyWarmUp proxyWarmUp) {
		this.aiClient = aiClient;
		this.artifactStorage = artifactStorage;
		this.proxyWarmUp = proxyWarmUp;
	}

	@Override
	public UUID requestAnalysis(AnalysisRequest request) {
		AnalysisAcceptedResponse accepted;
		try {
			accepted = request.requiresArtifactUpload()
					? aiClient.postMultipart(ANALYSES_PATH, toMultipart(request),
							AnalysisAcceptedResponse.class, request.idempotencyKey(), request.traceId())
					: aiClient.post(ANALYSES_PATH, toBody(request),
							AnalysisAcceptedResponse.class, request.idempotencyKey(), request.traceId());
		} catch (AiCallException exception) {
			throw new AnalysisServerException(toFailureCode(exception), exception.getMessage(), exception);
		} catch (SubmissionException exception) {
			// 저장된 ZIP 을 읽지 못한 경우다. AI 호출은 시도조차 못 했다.
			// ARCHIVE_INVALID 로 남기면 "교육생이 깨진 파일을 올렸다"가 되어 원인이 반대로 기록된다.
			throw new AnalysisServerException(AnalysisFailureCode.TEMPORARY_ERROR,
					"제출물 파일을 읽지 못해 분석을 요청하지 못했습니다: " + exception.getMessage(), exception);
		}

		if (accepted == null || accepted.jobId() == null || accepted.jobId().isBlank()) {
			// 원문은 AiPayloadLoggingInterceptor 가 "AI 응답 ◀ ..." 로 남긴다. 필드 이름이 달라서
			// (job_id 등) 못 읽은 것인지, AI가 정말 안 보낸 것인지는 그 줄을 봐야 갈린다.
			throw new AnalysisServerException(AnalysisFailureCode.MODEL_ERROR,
					"AI 서버가 202 응답에 jobId를 주지 않았다(읽어낸 값: jobId=null, status="
							+ (accepted == null ? "<본문 없음>" : accepted.status())
							+ "). 같은 traceId의 'AI 응답 ◀' 로그에서 원문을 확인한다: " + request.traceId());
		}
		UUID externalJobId = parseJobId(accepted.jobId());
		log.info("분석 요청 접수: submissionId={}, externalJobId={}, aiStatus={}, traceId={}",
				request.submissionId(), externalJobId, accepted.status(), request.traceId());
		return externalJobId;
	}

	@Override
	public Optional<AnalysisProgress> fetchProgress(UUID externalJobId) {
		try {
			return fetchProgressOnce(externalJobId);
		} catch (AiCallException exception) {
			// AI 앱이 스스로 "이 job을 모른다"고 명시한 404만 유실 신호로 다룬다(공통 봉투
			// {error:"JOB_NOT_FOUND"} — AiClient.translate()가 이미 failureCode로 옮겨 준다).
			//   WHY: 본문 없는 프록시·envoy 404(원본이 PAUSED이거나 라우팅 계층이 아직 준비되지
			//        않았을 때 앞단에서 나는 404)는 AI가 낸 신호가 아니다. 이것까지 유실로 접으면
			//        멀쩡한 job을 죽이고 LLM 호출을 다시 쓰게 된다.
			//   COST: 판별이 AI 응답 봉투에 의존한다. 봉투 없이 404만 오면 아래 웜업 경로로 간다.
			//   EXIT: AI가 모든 404에 JOB_NOT_FOUND를 싣게 되면 이 조건을 status==404만 보는
			//        것으로 되돌려도 된다.
			if (isJobNotFound(exception)) {
				return Optional.empty();
			}
			// HTML·빈 본문 등 JOB_NOT_FOUND가 아닌 404는 AI job 유실의 증거가 아니다. 원본 App Runner가
			// 잠들었거나 라우팅 계층이 준비되지 않았을 수 있으므로 프록시로 깨우고 한 번만 다시 확인한다.
			if (isNotFound(exception) && proxyWarmUp.warmUp()) {
				log.warn("프록시 계층 404로 판단해 웜업 후 분석 상태를 다시 조회한다: externalJobId={}", externalJobId);
				try {
					return fetchProgressOnce(externalJobId);
				} catch (AiCallException retryException) {
					if (isJobNotFound(retryException)) {
						return Optional.empty();
					}
					throw new AnalysisServerException(toFailureCode(retryException),
							retryException.getMessage(), retryException, isConnectionRefused(retryException));
				}
			}
			throw new AnalysisServerException(toFailureCode(exception), exception.getMessage(), exception,
					isConnectionRefused(exception));
		}
	}

	private Optional<AnalysisProgress> fetchProgressOnce(UUID externalJobId) {
		AnalysisJobStatusResponse response = aiClient.get(
				ANALYSES_PATH + "/" + externalJobId, AnalysisJobStatusResponse.class, null);
		if (response == null || response.status() == null) {
			throw new AnalysisServerException(AnalysisFailureCode.MODEL_ERROR,
					"AI 서버가 상태 없는 폴링 응답을 주었다: externalJobId=" + externalJobId);
		}
		return Optional.of(toProgress(response));
	}

	private static boolean isNotFound(AiCallException exception) {
		return exception.status() != null && exception.status().value() == HttpStatus.NOT_FOUND.value();
	}

	private static boolean isJobNotFound(AiCallException exception) {
		return isNotFound(exception) && "JOB_NOT_FOUND".equals(exception.failureCode());
	}

	/**
	 * {@code AiClient}가 연결 자체가 안 됐다고 판단한 경우인가. {@code AnalysisBatchService}가
	 * 배치 단위 조기 종료 판단에 쓰는 유일한 입력이다({@link AnalysisServerException#isConnectionLevel}
	 * javadoc 참고).
	 */
	private static boolean isConnectionRefused(AiCallException exception) {
		return "CONNECTION_REFUSED".equals(exception.failureCode());
	}

	/**
	 * {@code jobId}는 스펙상 {@code type: string}이지만 {@code analysis_job.external_job_id}는 UUID다.
	 *
	 * <p>실제 응답은 UUID였다(2026-08-09 확인). 그래도 파싱을 감싸 두는 이유는, 아닐 때 나는 예외가
	 * {@code IllegalArgumentException}이라 스택트레이스만 보고는 "AI가 UUID가 아닌 jobId를 줬다"를
	 * 알 수 없기 때문이다. 이 경우 재시도해도 같으므로 전송 실패가 아닌 {@code MODEL_ERROR}로 남긴다.
	 */
	private static UUID parseJobId(String raw) {
		try {
			return UUID.fromString(raw.trim());
		} catch (IllegalArgumentException exception) {
			throw new AnalysisServerException(AnalysisFailureCode.MODEL_ERROR,
					"AI 서버가 UUID가 아닌 jobId를 주었다: " + raw, exception);
		}
	}

	private static AnalysisProgress toProgress(AnalysisJobStatusResponse response) {
		AnalysisJobStatus status;
		try {
			status = AnalysisJobStatus.valueOf(response.status().trim());
		} catch (IllegalArgumentException exception) {
			throw new AnalysisServerException(AnalysisFailureCode.MODEL_ERROR,
					"AI 서버가 값 집합 밖의 status를 주었다: " + response.status(), exception);
		}
		return new AnalysisProgress(
				status,
				response.startedAt(),
				response.completedAt(),
				// 값 집합 밖이면 비워서 넘긴다. 어떻게 기록할지는 호출부가 정한다(AnalysisServerClient 문서).
				AnalysisFailureCode.parse(response.failureCode()).orElse(null),
				response.failureReason(),
				response.result(),
				response.aiUsage());
	}

	/**
	 * AI 명세 §5.2의 {@code failureCode}를 {@code analysis_job.failure_code} 12종으로 옮긴다.
	 *
	 * <p>대응되는 값이 없으면 {@code retryable}을 근거로 가른다 — 다시 불러서 풀릴 것은
	 * {@code TEMPORARY_ERROR}, 아니면 {@code MODEL_ERROR}다. 둘 다 12종 안에 있어 저장이 막히지 않는다.
	 *
	 * <p>⚠️ <b>이 변환은 오류 응답({@link AiCallException}) 경로에만 있다.</b> 폴링 본문의
	 * {@code failureCode}는 {@code fetchProgress}가 {@link AnalysisFailureCode#parse}로 바로 읽고
	 * 값 집합 밖이면 {@code null}로 넘기므로, {@code AnalysisBatchService}가 {@code MODEL_ERROR}로
	 * 대체한다 — 사유가 조용히 틀리게 기록되는 자리다. AI가 보내는 값 집합을 12종에 맞추는 것이
	 * 근본 해법이고, 그때까지는 원문이 {@code failure_reason}에 남는다.
	 */
	private static AnalysisFailureCode toFailureCode(AiCallException exception) {
		AnalysisFailureCode direct = AnalysisFailureCode.parse(exception.failureCode()).orElse(null);
		if (direct != null) {
			return direct;
		}
		String raw = exception.failureCode();
		if ("TIMEOUT".equals(raw)) {
			return AnalysisFailureCode.ANALYSIS_TIMEOUT;
		}
		if ("PROVIDER_ERROR".equals(raw) || "INVALID_JSON".equals(raw) || "CONTEXT_OVERFLOW".equals(raw)) {
			return AnalysisFailureCode.MODEL_ERROR;
		}
		return exception.retryable() ? AnalysisFailureCode.TEMPORARY_ERROR : AnalysisFailureCode.MODEL_ERROR;
	}

	/**
	 * ZIP 제출용 {@code multipart/form-data} 본문. 파트 이름은 AI 스펙 그대로 {@code payload}·{@code file}이다.
	 *
	 * <p>{@code payload}에 {@code Content-Type: application/json}을 명시한다. 스펙의
	 * {@code encoding.payload.contentType}이 그렇게 정하고 있고, 붙이지 않으면 FastAPI가 그 파트를
	 * 평범한 폼 필드로 읽어 {@code AnalysisRequest} 파싱이 통째로 실패한다.
	 */
	private MultiValueMap<String, Object> toMultipart(AnalysisRequest request) {
		HttpHeaders payloadHeaders = new HttpHeaders();
		payloadHeaders.setContentType(MediaType.APPLICATION_JSON);

		MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
		parts.add("payload", new HttpEntity<>(toBody(request), payloadHeaders));
		parts.add("file", artifactResource(request));
		return parts;
	}

	/**
	 * 파일 파트. 원본 파일명을 살려 보낸다 — AI 로그와 우리
	 * {@code submission_artifact.original_file_name}이 같은 이름을 가리켜야 대조가 된다.
	 *
	 * <p>{@code FileSystemResource}의 파일명은 저장 경로({@code <submissionId>.zip})라 원본과 다르다.
	 * 그래서 {@link Resource#getFilename()}만 덮어쓴다.
	 */
	private Resource artifactResource(AnalysisRequest request) {
		Resource stored = artifactStorage.load(request.artifactStorageUri());
		String fileName = request.artifactFileName() == null || request.artifactFileName().isBlank()
				? "submission.zip"
				: request.artifactFileName();
		return new FileSystemResourceWithName(stored, fileName);
	}

	/** 파일명만 바꿔 감싸는 데코레이터. 내용·길이는 원본에 그대로 위임한다. */
	private static final class FileSystemResourceWithName extends org.springframework.core.io.AbstractResource {

		private final Resource delegate;
		private final String fileName;

		private FileSystemResourceWithName(Resource delegate, String fileName) {
			this.delegate = delegate;
			this.fileName = fileName;
		}

		@Override
		public String getFilename() {
			return fileName;
		}

		@Override
		public String getDescription() {
			return delegate.getDescription();
		}

		@Override
		public java.io.InputStream getInputStream() throws java.io.IOException {
			return delegate.getInputStream();
		}

		@Override
		public long contentLength() throws java.io.IOException {
			// 길이를 위임하지 않으면 AbstractResource 가 스트림을 통째로 읽어 세는데,
			// 수십 MB ZIP 을 전송 전에 한 번 더 읽는 셈이 된다.
			return delegate.contentLength();
		}
	}

	private static AnalysesRequestBody toBody(AnalysisRequest request) {
		return new AnalysesRequestBody(
				request.method(),
				request.submissionId(),
				// ZIP 제출은 저장소가 없다 — source를 아예 생략한다("source": {}가 아니라 키 자체가 없어야
				// 2026-08-10 확인된 케이스3(ZIP)과 일치한다).
				request.repositoryUrl() == null
						? null
						: new AnalysisSource(request.repositoryUrl(), request.requestedBranch()),
				request.problemScope(),
				request.extractionScope(),
				request.commitEmail(),
				request.questionBudget(),
				toRequirementBodies(request.requirements()),
				toFocusItemBodies(request.focusItems()),
				toTeachBodies(request.teaches()),
				request.providerModelCode());
	}

	private static List<FocusItemBody> toFocusItemBodies(List<AnalysisServerClient.FocusItem> items) {
		return items == null ? null : items.stream()
				.map(item -> new FocusItemBody(item.focusItemId(), item.name(), item.description()))
				.toList();
	}

	private static List<RequirementBody> toRequirementBodies(List<AnalysisServerClient.RequirementItem> items) {
		return items == null ? null : items.stream()
				.map(item -> new RequirementBody(item.requirementId(), item.text()))
				.toList();
	}

	private static List<TeachBody> toTeachBodies(List<AnalysisServerClient.TeachItem> items) {
		return items == null ? null : items.stream()
				.map(item -> new TeachBody(item.id(), item.label(), item.unitId(), item.sourcePages()))
				.toList();
	}

	/**
	 * {@code POST /analyses} 요청 본문.
	 *
	 * <p>{@code NON_NULL}로 비는 필드를 아예 빼는 이유: 값이 없을 때 {@code null}을 명시로 보내면
	 * AI 쪽 기본값(빈 배열)을 {@code null}로 덮어쓸 수 있다. 필드를 생략하면 기본값이 그대로 적용된다.
	 * {@code TEAM_SHARED_PROBLEM}과 {@code INDIVIDUAL_OWN_COMMIT}은 {@code teaches}·{@code focusItems}
	 * 존재 여부를 AI가 상호 배타로 검증하므로(2026-08-10 확인), 이 생략이 단순 최적화가 아니라
	 * <b>필수</b>다 — 해당 없는 쪽에 빈 배열이라도 실리면 AI가 거부한다.
	 *
	 * <p>{@code curriculumVersionId}·{@code attemptId} 필드가 없는 이유: AI가 이 값을 읽는 코드가
	 * 없다고 확인돼(2026-08-10) 계약에서 아예 뺐다.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private record AnalysesRequestBody(
			String method,
			UUID submissionId,
			AnalysisSource source,
			String problemScope,
			String extractionScope,
			String commitEmail,
			Integer questionBudget,
			List<RequirementBody> requirements,
			List<FocusItemBody> focusItems,
			List<TeachBody> teaches,
			String providerModelCode
	) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	private record AnalysisSource(String repoUrl, String branch) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	private record RequirementBody(String requirementId, String text) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	private record FocusItemBody(String focusItemId, String name, String description) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	private record TeachBody(
			String id,
			String label,
			String unitId,
			List<Integer> sourcePages
	) {
	}

	/** 202 응답. {@code status}는 항상 {@code QUEUED}라 읽지 않는다. */
	private record AnalysisAcceptedResponse(String jobId, String status) {
	}

	/**
	 * 폴링 응답.
	 *
	 * <p>Jackson 기본값이 {@code FAIL_ON_UNKNOWN_PROPERTIES=false}라 나머지 필드는 그냥 무시된다.
	 * 덕분에 AI가 필드를 <b>추가</b>하는 변경으로는 이 매핑이 깨지지 않는다.
	 */
	private record AnalysisJobStatusResponse(
			String jobId,
			String status,
			String failureReason,
			String failureCode,
			Instant startedAt,
			Instant completedAt,
			AnalysisResultPayload result,
			List<AiUsageEntry> aiUsage
	) {
	}
}
