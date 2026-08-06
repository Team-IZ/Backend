package com.bigproject.backend.domain.submission.application;

import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisJobRepository;
import com.bigproject.backend.domain.submission.domain.GithubRepositoryUrl;
import com.bigproject.backend.domain.submission.domain.RepositoryVerification;
import com.bigproject.backend.domain.submission.domain.Submission;
import com.bigproject.backend.domain.submission.domain.SubmissionArtifact;
import com.bigproject.backend.domain.submission.domain.SubmissionErrorCode;
import com.bigproject.backend.domain.submission.domain.SubmissionException;
import com.bigproject.backend.domain.submission.infrastructure.RepositoryVerificationRepository;
import com.bigproject.backend.domain.submission.infrastructure.SubmissionArtifactRepository;
import com.bigproject.backend.domain.submission.infrastructure.SubmissionContextRepository;
import com.bigproject.backend.domain.submission.infrastructure.SubmissionContextRepository.SubmissionContext;
import com.bigproject.backend.domain.submission.infrastructure.SubmissionRepository;
import com.bigproject.backend.domain.submission.presentation.dto.CreateGithubSubmissionRequest;
import com.bigproject.backend.domain.submission.presentation.dto.SubmissionAnalysisResponse;
import com.bigproject.backend.domain.submission.presentation.dto.SubmissionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
 * 저장소 검증·코드 fetch·분석이 전부 회차 마감 후 배치로 이동했다. 제출 시점에 백엔드가 판정할 수 있는 것은
 * URL 형식·호스트와 ZIP의 크기·압축 형식뿐이고, 그 밖의 실패는 분석 단계의 사건이다.
 */
@Service
@RequiredArgsConstructor
public class SubmissionService {

	private static final String ROUND_STATUS_OPEN = "OPEN";
	private static final String ZIP_ARTIFACT_TYPE = "ZIP_WITH_GITLOG";

	private final SubmissionRepository submissionRepository;
	private final RepositoryVerificationRepository repositoryVerificationRepository;
	private final SubmissionArtifactRepository submissionArtifactRepository;
	private final SubmissionContextRepository submissionContextRepository;
	private final AnalysisJobRepository analysisJobRepository;
	private final SubmissionArtifactStorage artifactStorage;

	/**
	 * 파일당 상한. 정의서에 원천 컬럼이 없어 애플리케이션 상수로 두고 적용값을
	 * {@code submission_artifact.applied_max_file_bytes}에 그대로 기록한다(결정 ③).
	 */
	@Value("${app.submission.max-zip-bytes}")
	private long maxZipBytes;

	/**
	 * GitHub 저장소 URL 제출·재제출.
	 *
	 * <p>{@code repository_verification}을 반드시 함께 만든다. {@code submission.repository_id}가 분석 성공
	 * 전까지 NULL이라 제출된 URL 원문이 남는 자리가 그 행뿐이기 때문이다.
	 */
	@Transactional
	public SubmissionResponse submitGithubUrl(UUID userId, CreateGithubSubmissionRequest request, UUID requestId) {
		SubmissionContext context = requireSubmittableRound(userId, request.assessmentRoundId());
		if (!context.getAllowGithubIntegration()) {
			throw new SubmissionException(SubmissionErrorCode.SUBMISSION_METHOD_NOT_ALLOWED);
		}

		// 멱등: 같은 X-Request-Id로 이미 접수했다면 그때 만든 제출을 그대로 돌려준다.
		// uq_submission_current는 이를 막지 못한다 — 두 번째 제출이 첫 번째를 supersede할 뿐이다.
		Submission replayed = findReplayedSubmission(requestId);
		if (replayed != null) {
			return SubmissionResponse.of(replayed, null);
		}

		GithubRepositoryUrl repositoryUrl = GithubRepositoryUrl.parse(request.repositoryUrl());
		Instant requestedAt = Instant.now();

		RepositoryVerification verification = repositoryVerificationRepository.save(RepositoryVerification.pending(
				context.getOrgId(),
				context.getTeamId(),
				repositoryUrl.normalized(),
				normalizeBranch(request.branch()),
				requestedAt,
				requestId
		));

		UUID supersededId = supersedeCurrentSubmission(context.getTeamId(), request.assessmentRoundId());

		// submitted_at은 verification.requested_at을 그대로 쓴다. 나중 시각을 쓰면 마감 직전 제출이
		// 확인에 걸린 시간만큼 밀려 LATE/MISSED로 잘못 판정된다.
		Submission submission = submissionRepository.save(Submission.acceptGithubUrl(
				context.getOrgId(),
				context.getTeamId(),
				request.assessmentRoundId(),
				normalizeBranch(request.branch()),
				supersededId,
				userId,
				requestedAt,
				verification.getVerificationId()
		));

		return SubmissionResponse.of(submission, null);
	}

	/**
	 * ZIP 업로드·재업로드.
	 *
	 * <p>{@code ck_submission_method_2}의 ZIP 분기가 저장소·커밋 컬럼을 전부 NULL로 요구하므로 GitHub과 달리
	 * {@code VALIDATING} 행을 먼저 만들 수 있다. 접수를 먼저 확정해두면 마감 직전 후속 처리 장애로 제출이
	 * 거부되어 교육생이 마감을 놓치는 사고를 막을 수 있다.
	 *
	 * <p>여기서 판정하는 것은 크기와 압축 형식뿐이다. {@code EMPTY_CODE}·{@code GIT_LOG_MISSING}과 안전 추출은
	 * 아직 구현되지 않았고, 그 때문에 제출은 {@code VALIDATING}에 머문다.
	 */
	@Transactional
	public SubmissionResponse submitZip(UUID userId, UUID assessmentRoundId, MultipartFile file) {
		SubmissionContext context = requireSubmittableRound(userId, assessmentRoundId);
		if (!context.getAllowZipSubmission()) {
			throw new SubmissionException(SubmissionErrorCode.SUBMISSION_METHOD_NOT_ALLOWED);
		}
		requireReadableZip(file);

		UUID supersededId = supersedeCurrentSubmission(context.getTeamId(), assessmentRoundId);

		Submission submission = submissionRepository.save(Submission.receiveZipUpload(
				context.getOrgId(),
				context.getTeamId(),
				assessmentRoundId,
				supersededId,
				userId,
				Instant.now()
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
		Submission submission = submissionRepository.findById(submissionId)
				.orElseThrow(() -> new SubmissionException(SubmissionErrorCode.SUBMISSION_NOT_FOUND));

		// 제출은 팀 단위라 같은 팀이면 누가 조회해도 같은 결과가 나와야 한다.
		SubmissionContext context = submissionContextRepository
				.findSubmissionContext(userId, submission.getAssessmentRoundId())
				.orElseThrow(() -> new SubmissionException(SubmissionErrorCode.SUBMISSION_ACCESS_DENIED));
		if (!context.getTeamId().equals(submission.getTeamId())) {
			throw new SubmissionException(SubmissionErrorCode.SUBMISSION_ACCESS_DENIED);
		}

		return analysisJobRepository
				.findFirstBySubmissionIdOrderByExecutionNoDescStartedAtDescJobIdDesc(submissionId)
				.map(SubmissionAnalysisResponse::of)
				.orElseGet(() -> SubmissionAnalysisResponse.notStarted(submissionId));
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

	private Submission findReplayedSubmission(UUID requestId) {
		return repositoryVerificationRepository.findByRequestId(requestId)
				.flatMap(verification -> submissionRepository
						.findByRepositoryVerificationId(verification.getVerificationId()))
				.orElse(null);
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
