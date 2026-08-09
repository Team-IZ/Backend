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
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link AnalysisServerClient}의 실제 구현. AI(FastAPI)의 {@code POST /analyses}·
 * {@code GET /analyses/{jobId}}를 부른다.
 *
 * <p>이 빈이 등록되면 {@link AnalysisServerClientConfig}의 "항상 실패하는" 폴백이
 * {@code @ConditionalOnMissingBean}에 의해 물러난다.
 *
 * <p><b>경로에 {@code /api/v0}를 붙이지 않는다.</b> {@code ai.base-url}이 이미 그 접두어를 포함한다
 * ({@code application.yaml}의 {@code AI_BASE_URL} 기본값 참조). 여기서 또 붙이면 {@code /api/v0/api/v0}가 된다.
 *
 * <h2>실패를 우리 값 집합으로 접는다</h2>
 *
 * <p>{@link AiClient}는 모든 실패를 {@link AiCallException} 하나로 주는데, 그 {@code failureCode}는
 * AI 명세 §5.2의 값(TIMEOUT·PROVIDER_ERROR 등)이라 {@code ck_analysis_job_failure_code_2}의 15종과
 * 다르다. 그대로 저장하면 CHECK 위반으로 <b>실패를 기록조차 못 하게</b> 되므로 여기서 변환한다.
 */
@Slf4j
@Component
public class HttpAnalysisServerClient implements AnalysisServerClient {

	private static final String ANALYSES_PATH = "/analyses";

	private final AiClient aiClient;
	private final SubmissionArtifactStorage artifactStorage;

	public HttpAnalysisServerClient(AiClient aiClient, SubmissionArtifactStorage artifactStorage) {
		this.aiClient = aiClient;
		this.artifactStorage = artifactStorage;
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
			throw new AnalysisServerException(AnalysisFailureCode.MODEL_ERROR,
					"AI 서버가 202 응답에 jobId를 주지 않았다.");
		}
		return parseJobId(accepted.jobId());
	}

	@Override
	public Optional<AnalysisProgress> fetchProgress(UUID externalJobId) {
		try {
			AnalysisJobStatusResponse response = aiClient.get(
					ANALYSES_PATH + "/" + externalJobId, AnalysisJobStatusResponse.class, null);
			if (response == null || response.status() == null) {
				throw new AnalysisServerException(AnalysisFailureCode.MODEL_ERROR,
						"AI 서버가 상태 없는 폴링 응답을 주었다: externalJobId=" + externalJobId);
			}
			return Optional.of(toProgress(response));
		} catch (AiCallException exception) {
			// 404는 오류가 아니다. AI가 job을 메모리에만 두어 재시작하면 사라진다고 스펙에 명시돼
			// 있고(GET /analyses/{job_id} 설명), 호출부는 이를 재요청 신호로 다룬다.
			if (exception.status() != null && exception.status().value() == HttpStatus.NOT_FOUND.value()) {
				return Optional.empty();
			}
			throw new AnalysisServerException(toFailureCode(exception), exception.getMessage(), exception);
		}
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
	 * AI 명세 §5.2의 {@code failureCode}를 {@code analysis_job.failure_code} 15종으로 옮긴다.
	 *
	 * <p>대응되는 값이 없으면 {@code retryable}을 근거로 가른다 — 다시 불러서 풀릴 것은
	 * {@code TEMPORARY_ERROR}, 아니면 {@code MODEL_ERROR}다. 둘 다 15종 안에 있어 저장이 막히지 않는다.
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
				request.attemptId(),
				new AnalysisSource(request.repositoryUrl(), request.requestedBranch()),
				request.extractionScope(),
				request.commitEmail(),
				request.questionBudget(),
				request.curriculumVersionId(),
				request.providerModelCode());
	}

	/**
	 * {@code POST /analyses} 요청 본문.
	 *
	 * <p>{@code NON_NULL}로 비는 필드를 아예 빼는 이유: {@code focusItems}·{@code requirements}·
	 * {@code teaches}는 아직 채우지 않는데, {@code null}을 명시로 보내면 AI 쪽 기본값(빈 배열)을
	 * {@code null}로 덮어쓸 수 있다. 필드를 생략하면 기본값이 그대로 적용된다.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private record AnalysesRequestBody(
			String method,
			UUID submissionId,
			UUID attemptId,
			AnalysisSource source,
			String extractionScope,
			String commitEmail,
			Integer questionBudget,
			UUID curriculumVersionId,
			String providerModelCode
	) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	private record AnalysisSource(String repoUrl, String branch) {
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
			java.util.List<AiUsageEntry> aiUsage
	) {
	}
}
