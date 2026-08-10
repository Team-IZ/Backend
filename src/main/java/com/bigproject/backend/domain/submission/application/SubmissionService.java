package com.bigproject.backend.domain.submission.application;

import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisJobRepository;
import com.bigproject.backend.domain.submission.domain.GithubRepositoryUrl;
import com.bigproject.backend.domain.submission.domain.Repository;
import com.bigproject.backend.domain.submission.domain.RepositoryStatus;
import com.bigproject.backend.domain.submission.domain.Submission;
import com.bigproject.backend.domain.submission.domain.SubmissionArtifact;
import com.bigproject.backend.domain.submission.domain.SubmissionAcceptedEvent;
import com.bigproject.backend.domain.submission.domain.SubmissionErrorCode;
import com.bigproject.backend.domain.submission.domain.SubmissionException;
import com.bigproject.backend.domain.submission.infrastructure.GithubRepositoryRepository;
import com.bigproject.backend.domain.submission.infrastructure.JdbcMeasurementAttemptOpener;
import com.bigproject.backend.domain.submission.infrastructure.SubmissionArtifactRepository;
import com.bigproject.backend.domain.submission.infrastructure.SubmissionContextRepository;
import com.bigproject.backend.domain.submission.infrastructure.SubmissionContextRepository.SubmissionContext;
import com.bigproject.backend.domain.submission.infrastructure.SubmissionRepository;
import com.bigproject.backend.domain.submission.presentation.dto.CreateGithubSubmissionRequest;
import com.bigproject.backend.domain.codeanalysis.infrastructure.JdbcAnalysisResultQueryRepository;
import com.bigproject.backend.domain.codeanalysis.infrastructure.JdbcAnalysisResultQueryRepository.AnalysisSummary;
import com.bigproject.backend.domain.submission.presentation.dto.SubmissionAnalysisResponse;
import com.bigproject.backend.domain.submission.presentation.dto.SubmissionAnalysisResultResponse;
import com.bigproject.backend.domain.submission.presentation.dto.SubmissionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.zip.ZipInputStream;

/**
 * 코드 제출 접수와 분석 상태 조회.
 *
 * <p><b>이 서비스는 AI 서버를 호출하지 않는다.</b> GitHub 접근 주체가 AI 서버로 확정되면서(2026-08-06)
 * 저장소 검증·코드 fetch·분석이 전부 이쪽 책임에서 빠졌다. 제출 시점에 백엔드가 판정할 수 있는 것은
 * URL 형식·호스트와 ZIP의 크기·압축 형식뿐이고, 그 밖의 실패는 분석 단계의 사건이다.
 *
 * <p>GitHub 제출이 성공하면 {@link com.bigproject.backend.domain.submission.domain.SubmissionAcceptedEvent}를
 * 발행해 분석을 즉시 트리거한다(2026-08-07). "마감 후 배치 1회"였던 종전 정책을 바꾼 것으로, 팀이 마감 전
 * 재제출할 때마다 AI 호출 비용이 다시 드는 대신 저장소 URL 오타 같은 실수를 마감 전에 알고 고칠 수 있다.
 * 실제 AI 호출은 이 서비스가 아니라 {@code codeanalysis} 모듈의 배치가 이벤트를 받아 수행한다.
 */
@Service
@RequiredArgsConstructor
public class SubmissionService {

	private static final String ROUND_STATUS_OPEN = "OPEN";
	private static final String ZIP_ARTIFACT_TYPE = "ZIP_WITH_GITLOG";

	private final SubmissionRepository submissionRepository;
	private final GithubRepositoryRepository githubRepositoryRepository;
	private final SubmissionArtifactRepository submissionArtifactRepository;
	private final SubmissionContextRepository submissionContextRepository;
	private final AnalysisJobRepository analysisJobRepository;
	private final SubmissionArtifactStorage artifactStorage;
	private final JdbcAnalysisResultQueryRepository analysisResultQueryRepository;
	private final JdbcMeasurementAttemptOpener measurementAttemptOpener;
	private final ApplicationEventPublisher eventPublisher;

	/**
	 * 파일당 상한. 정의서에 원천 컬럼이 없어 애플리케이션 상수로 두고 적용값을
	 * {@code submission_artifact.applied_max_file_bytes}에 그대로 기록한다(결정 ③).
	 */
	@Value("${app.submission.max-zip-bytes}")
	private long maxZipBytes;

	/**
	 * GitHub 저장소 URL 제출·재제출.
	 *
	 * <p><b>{@code repository_verification}을 여기서 만들지 않는다.</b> 그 행의 {@code requested_at}이
	 * "코드 분석이 저장소 확인을 시작한 시각"으로 재정의되면서(S-12) 제출 시점에는 채울 값이 없다.
	 * 확인 실행 행은 분석 시작 시점에 만들어진다.
	 *
	 * <p>제출된 URL은 팀의 ACTIVE {@code repository} 행이 보관한다. 브랜치는 제출마다 다를 수 있어
	 * {@code submission.requested_branch}가 따로 갖는다.
	 *
	 * <p>접수에 성공하면 {@link SubmissionAcceptedEvent}를 발행한다(2026-08-07). 분석 트리거가
	 * "마감 후 배치"에서 "제출 즉시"로 바뀌면서, 이 트랜잭션이 커밋된 뒤 분석 배치가 백그라운드로
	 * 이어받는다. 이벤트를 이 메서드가 직접 발행하는 것이 아니라 트랜잭션 커밋 후 리스너가 받는 이유는
	 * AI 서버 호출이 이 제출의 DB 트랜잭션을 붙든 채 일어나면 안 되기 때문이다 — 마감 직전 동시 제출에서
	 * 커넥션 풀이 마른다.
	 */
	@Transactional
	public SubmissionResponse submitGithubUrl(
			UUID userId, CreateGithubSubmissionRequest request, UUID idempotencyKey) {
		SubmissionContext context = requireSubmittableRound(userId, request.assessmentRoundId());
		if (!context.getAllowGithubIntegration()) {
			throw new SubmissionException(SubmissionErrorCode.SUBMISSION_METHOD_NOT_ALLOWED);
		}

		// 멱등: 같은 키로 이미 접수했다면 그때 만든 제출을 그대로 돌려준다.
		// uq_submission_current는 이를 막지 못한다 — 두 번째 제출이 첫 번째를 supersede할 뿐이다.
		Submission replayed = submissionRepository.findByRequestIdempotencyKey(idempotencyKey).orElse(null);
		if (replayed != null) {
			return SubmissionResponse.of(requireSameTarget(replayed, request.assessmentRoundId()), null);
		}

		GithubRepositoryUrl repositoryUrl = GithubRepositoryUrl.parse(request.repositoryUrl());
		Instant submittedAt = Instant.now();
		Repository repository = upsertTeamRepository(context, repositoryUrl, submittedAt);

		UUID supersededId = supersedeCurrentSubmission(context.getTeamId(), request.assessmentRoundId());

		Submission submission = submissionRepository.save(Submission.acceptGithubUrl(
				context.getOrgId(),
				context.getTeamId(),
				request.assessmentRoundId(),
				repository.getRepositoryId(),
				normalizeBranch(request.branch()),
				supersededId,
				userId,
				submittedAt,
				idempotencyKey
		));

		// 개인 응시를 여기서 연다. 분석 성공 시점에 만들면 분석이 실패했을 때 응시가 영영 생기지
		// 않아 "미응시"와 "분석 실패로 응시 불가"가 구분되지 않는다.
		openAttempts(context, request.assessmentRoundId(), submission);

		eventPublisher.publishEvent(new SubmissionAcceptedEvent(submission.getSubmissionId()));

		return SubmissionResponse.of(submission, null);
	}

	/**
	 * 팀원마다 개인 응시를 연다. <b>제출 행을 먼저 flush한다.</b>
	 *
	 * <p>{@code Submission}은 {@code @UuidGenerator}라 {@code save()}가 메모리에서 id를 채운 객체를
	 * 곧바로 돌려주지만, INSERT 자체는 쓰기 지연으로 flush 시점까지 미뤄진다. 그런데
	 * {@link JdbcMeasurementAttemptOpener}는 {@code JdbcTemplate}이라 Hibernate가 이 호출을 모르고
	 * auto-flush를 걸지 않는다 — 같은 커넥션·같은 트랜잭션이어도 DB에는 아직 그 제출이 없다.
	 *
	 * <p>그 상태로 {@code measurement_attempt.source_submission_id}에 새 제출 id를 쓰면
	 * {@code fk_measurement_attempt_source_submission_id}가 막고, 전역 핸들러가 이를 409
	 * {@code DATA_INTEGRITY_VIOLATION}으로 바꿔 <b>제출·재제출이 통째로 거부된다.</b>
	 *
	 * <p>{@link #supersedeCurrentSubmission}이 이미 같은 이유로 flush를 강제하고 있다 — JPA 지연 쓰기와
	 * 생 JDBC를 섞는 자리마다 이 경계가 필요하다.
	 */
	private void openAttempts(SubmissionContext context, UUID assessmentRoundId, Submission submission) {
		submissionRepository.flush();
		measurementAttemptOpener.openForTeam(context.getOrgId(), context.getTeamId(),
				assessmentRoundId, submission.getSubmissionId());
	}

	/**
	 * 팀의 현재 저장소를 확정한다. 없으면 만들고, 있으면 주소만 갱신한다.
	 *
	 * <p>새 행을 만들지 않는 이유는 {@code uq_repository_active_per_team}이 팀당 ACTIVE 1건을 강제하기
	 * 때문이다. 재제출로 주소가 바뀌면 기존 행이 새 주소를 가리키게 되고, <b>덮어쓴 이전 주소는 남지
	 * 않는다.</b> 마감 후 배치는 {@code is_current=TRUE} 제출만 분석하므로 기능상 문제는 없지만,
	 * 과거 제출의 주소를 되돌아볼 수는 없다.
	 */
	private Repository upsertTeamRepository(
			SubmissionContext context, GithubRepositoryUrl url, Instant now) {
		return githubRepositoryRepository.findByTeamIdAndStatus(context.getTeamId(), RepositoryStatus.ACTIVE)
				.map(existing -> {
					existing.changeRepositoryUrl(url.original(), url.normalized(), now);
					return existing;
				})
				.orElseGet(() -> githubRepositoryRepository.save(Repository.active(
						context.getProjectId(),
						context.getTeamId(),
						context.getOrgId(),
						url.original(),
						url.normalized(),
						now
				)));
	}

	/**
	 * ZIP 업로드·재업로드.
	 *
	 * <p>{@code ck_submission_method_2}의 ZIP 분기가 저장소·커밋 컬럼을 전부 NULL로 요구하므로 GitHub과 달리
	 * {@code VALIDATING} 행을 먼저 만들 수 있다. 접수를 먼저 확정해두면 마감 직전 후속 처리 장애로 제출이
	 * 거부되어 교육생이 마감을 놓치는 사고를 막을 수 있다.
	 *
	 * <p>여기서 판정하는 것은 크기와 압축 형식뿐이다. {@code EMPTY_CODE}·{@code GIT_LOG_MISSING}은
	 * AI가 분석 중에 {@code failureCode}로 돌려주므로 백엔드가 볼 것이 없다.
	 *
	 * <p>{@link SubmissionAcceptedEvent}를 발행한다(2026-08-09). AI의
	 * {@code POST /api/v0/analyses}에 {@code multipart/form-data} 경로가 생겨 ZIP을 실어 보낼 수 있게
	 * 되면서 보류가 풀렸다. GitHub 경로와 같은 트리거를 쓴다.
	 */
	@Transactional
	public SubmissionResponse submitZip(
			UUID userId, UUID assessmentRoundId, MultipartFile file, UUID idempotencyKey) {
		SubmissionContext context = requireSubmittableRound(userId, assessmentRoundId);
		if (!context.getAllowZipSubmission()) {
			throw new SubmissionException(SubmissionErrorCode.SUBMISSION_METHOD_NOT_ALLOWED);
		}

		// 멱등 판정을 파일 검증보다 먼저 한다. 재시도된 업로드를 다시 읽고 저장하는 비용을 아낄 수 있고,
		// 수십 MB 업로드에서는 그 차이가 크다.
		Submission replayed = submissionRepository.findByRequestIdempotencyKey(idempotencyKey).orElse(null);
		if (replayed != null) {
			UUID artifactId = submissionArtifactRepository.findBySubmissionId(replayed.getSubmissionId())
					.map(SubmissionArtifact::getArtifactId)
					.orElse(null);
			return SubmissionResponse.of(requireSameTarget(replayed, assessmentRoundId), artifactId);
		}

		requireReadableZip(file);

		UUID supersededId = supersedeCurrentSubmission(context.getTeamId(), assessmentRoundId);

		Submission submission = submissionRepository.save(Submission.receiveZipUpload(
				context.getOrgId(),
				context.getTeamId(),
				assessmentRoundId,
				supersededId,
				userId,
				Instant.now(),
				idempotencyKey
		));

		// 저장 키에 submissionId가 들어가야 해서 INSERT 이후에 저장한다. 트랜잭션이 뒤에서 롤백되면
		// 참조되지 않는 파일이 남지만, 같은 제출이 다시 오면 같은 키를 덮어쓰므로 누적되지는 않는다.
		SubmissionArtifactStorage.StoredArtifact stored;
		try (InputStream content = file.getInputStream()) {
			stored = artifactStorage.store(
					context.getOrgId(), context.getTeamId(), submission.getSubmissionId(), content);
		} catch (IOException exception) {
			throw new SubmissionException(SubmissionErrorCode.ARTIFACT_STORE_FAILED,
					SubmissionErrorCode.ARTIFACT_STORE_FAILED.defaultMessage(), exception);
		}

		SubmissionArtifact artifact = submissionArtifactRepository.save(SubmissionArtifact.validating(
				submission.getSubmissionId(),
				ZIP_ARTIFACT_TYPE,
				resolveOriginalFileName(file),
				file.getContentType() == null ? "application/zip" : file.getContentType(),
				stored.storageUri(),
				stored.contentHash(),
				stored.sizeBytes(),
				maxZipBytes
		));

		openAttempts(context, assessmentRoundId, submission);

		// artifact 를 저장한 뒤에 발행한다. 리스너가 storage_uri 를 읽어 AI 에 파일을 실어 보내므로
		// 순서가 뒤집히면 분석 요청이 파일을 찾지 못한다.
		eventPublisher.publishEvent(new SubmissionAcceptedEvent(submission.getSubmissionId()));

		return SubmissionResponse.of(submission, artifact.getArtifactId());
	}

	/**
	 * 분석 진행 상태와 실패 사유.
	 *
	 * <p>{@code code_analysis}가 아니라 {@code analysis_job}을 읽는다. 전자는 성공했을 때에만 생기는 결과물이라
	 * "진행 중"과 "분석 없음"을 구분할 수 없고 실패 사유 컬럼도 없다.
	 */
	@Transactional(readOnly = true)
	public SubmissionAnalysisResponse getAnalysis(UUID userId, UUID submissionId) {
		// 제출은 팀 단위라 같은 팀이면 누가 조회해도 같은 결과가 나와야 한다.
		requireTeamSubmission(userId, submissionId);

		return analysisJobRepository
				.findFirstBySubmissionIdOrderByExecutionNoDescStartedAtDescJobIdDesc(submissionId)
				.map(SubmissionAnalysisResponse::of)
				.orElseGet(() -> SubmissionAnalysisResponse.notStarted(submissionId));
	}

	/**
	 * 분석 결과 본체.
	 *
	 * <p>진행 상태({@link #getAnalysis})와 나눈 이유: 폴링은 초 단위로 도는데 결과는 한 번만 읽는다.
	 * 한 응답에 합치면 "분석 중"을 확인하는 요청마다 문제·근거·커밋을 함께 조회하게 된다.
	 *
	 * <p>세션만 조회자 본인 것을 돌려준다. 제출은 팀 단위지만 응시는 개인 단위라, 같은 제출을 조회해도
	 * {@code session}은 사람마다 다르다.
	 *
	 * @throws SubmissionException 분석이 아직 성공하지 않아 결과가 없을 때
	 */
	@Transactional(readOnly = true)
	public SubmissionAnalysisResultResponse getAnalysisResult(UUID userId, UUID submissionId) {
		Submission submission = requireTeamSubmission(userId, submissionId);

		AnalysisSummary summary = analysisResultQueryRepository
				.findActiveAnalysis(submission.getSubmissionId())
				.orElseThrow(() -> new SubmissionException(SubmissionErrorCode.ANALYSIS_RESULT_NOT_FOUND));

		return new SubmissionAnalysisResultResponse(
				submissionId,
				summary.analysisId(),
				summary.appliedScope(),
				summary.scopeFallback(),
				summary.fallbackReason(),
				summary.resolvedBranch(),
				summary.headCommit(),
				summary.analyzedAt(),
				analysisResultQueryRepository.findProblems(submissionId),
				analysisResultQueryRepository.findRequirementResults(submissionId),
				analysisResultQueryRepository.findMySession(submissionId, userId).orElse(null));
	}

	/** 제출을 찾고 조회자가 같은 팀인지 확인한다. 상태 조회와 결과 조회가 같은 규칙을 쓰게 묶어 둔다. */
	private Submission requireTeamSubmission(UUID userId, UUID submissionId) {
		Submission submission = submissionRepository.findById(submissionId)
				.orElseThrow(() -> new SubmissionException(SubmissionErrorCode.SUBMISSION_NOT_FOUND));
		SubmissionContext context = submissionContextRepository
				.findSubmissionContext(userId, submission.getAssessmentRoundId())
				.orElseThrow(() -> new SubmissionException(SubmissionErrorCode.SUBMISSION_ACCESS_DENIED));
		if (!context.getTeamId().equals(submission.getTeamId())) {
			throw new SubmissionException(SubmissionErrorCode.SUBMISSION_ACCESS_DENIED);
		}
		return submission;
	}

	/**
	 * 제출 가능 조건을 서버가 직접 확인한다.
	 *
	 * <p>{@code trainee_home_round_view.can_submit}을 신뢰하지 않는 이유는 그 컬럼이 {@code round_status}를
	 * 보지 않아 {@code PLANNED} 회차도 제출 가능으로 반환하기 때문이다.
	 */
	private SubmissionContext requireSubmittableRound(UUID userId, UUID assessmentRoundId) {
		SubmissionContext context = submissionContextRepository.findSubmissionContext(userId, assessmentRoundId)
				.orElseThrow(() -> new SubmissionException(SubmissionErrorCode.SUBMISSION_ROUND_NOT_ACCESSIBLE));

		if (!ROUND_STATUS_OPEN.equals(context.getRoundStatus())) {
			throw new SubmissionException(SubmissionErrorCode.SUBMISSION_ROUND_NOT_OPEN);
		}
		if (!context.getSubmissionDueAt().isAfter(Instant.now())) {
			throw new SubmissionException(SubmissionErrorCode.SUBMISSION_DEADLINE_PASSED);
		}
		return context;
	}

	/**
	 * 팀·회차의 현재 제출을 내리고 그 식별자를 돌려준다.
	 *
	 * <p>{@code uq_submission_current(team_id, assessment_round_id) WHERE is_current=TRUE} 때문에 새 행을
	 * 넣기 전에 기존 행이 내려가야 한다. JPA는 쓰기 지연으로 INSERT를 먼저 보낼 수 있어 여기서 flush를 강제한다.
	 */
	private UUID supersedeCurrentSubmission(UUID teamId, UUID assessmentRoundId) {
		return submissionRepository.findByTeamIdAndAssessmentRoundIdAndCurrentIsTrue(teamId, assessmentRoundId)
				.map(current -> {
					current.supersede();
					submissionRepository.flush();
					return current.getSubmissionId();
				})
				.orElse(null);
	}

	/**
	 * 멱등 재생은 "같은 요청을 다시 보낸 것"일 때만 옳다. 같은 키가 다른 회차에 재사용됐다면 최초 결과를
	 * 돌려주는 것이 오히려 위험하다 — 교육생은 방금 고른 회차에 제출했다고 믿지만 실제로는 이전 회차
	 * 제출을 보게 되고, 새 회차는 미제출로 남는다.
	 */
	private Submission requireSameTarget(Submission replayed, UUID requestedRoundId) {
		if (!replayed.getAssessmentRoundId().equals(requestedRoundId)) {
			throw new SubmissionException(SubmissionErrorCode.IDEMPOTENCY_KEY_CONFLICT);
		}
		return replayed;
	}

	/** 크기와 압축 형식만 본다. 내용 판정(EMPTY_CODE·GIT_LOG_MISSING)은 안전 추출과 함께 별건이다. */
	private void requireReadableZip(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new SubmissionException(SubmissionErrorCode.ARCHIVE_INVALID, "빈 파일은 제출할 수 없습니다.");
		}
		if (file.getSize() > maxZipBytes) {
			throw new SubmissionException(SubmissionErrorCode.FILE_TOO_LARGE);
		}

		// 확장자나 Content-Type이 아니라 실제로 엔트리를 읽어 판정한다. 둘 다 클라이언트가 정하는 값이다.
		try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
			if (zip.getNextEntry() == null) {
				throw new SubmissionException(SubmissionErrorCode.ARCHIVE_INVALID, "빈 압축 파일입니다.");
			}
		} catch (IOException exception) {
			throw new SubmissionException(SubmissionErrorCode.ARCHIVE_INVALID,
					SubmissionErrorCode.ARCHIVE_INVALID.defaultMessage(), exception);
		}
	}

	/**
	 * 업로드된 파일명은 표시용으로만 보존한다. 저장 경로에는 쓰지 않으므로 경로 순회 위험은 없고,
	 * 컬럼 길이(255)만 지키면 된다.
	 */
	private String resolveOriginalFileName(MultipartFile file) {
		String name = file.getOriginalFilename();
		if (name == null || name.isBlank()) {
			return "submission.zip";
		}
		return name.length() > 255 ? name.substring(name.length() - 255) : name;
	}

	private String normalizeBranch(String branch) {
		return branch == null || branch.isBlank() ? null : branch.trim();
	}
}
