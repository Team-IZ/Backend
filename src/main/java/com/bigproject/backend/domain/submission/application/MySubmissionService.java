package com.bigproject.backend.domain.submission.application;

import com.bigproject.backend.domain.submission.domain.MySubmissionQueryRepository;
import com.bigproject.backend.domain.submission.domain.MySubmissionQueryRepository.MySubmissionRow;
import com.bigproject.backend.domain.submission.domain.SubmissionErrorCode;
import com.bigproject.backend.domain.submission.domain.SubmissionException;
import com.bigproject.backend.domain.submission.presentation.dto.MySubmissionResponse;
import com.bigproject.backend.domain.submission.presentation.dto.MySubmissionResponse.LastCommit;
import com.bigproject.backend.domain.submission.presentation.dto.MySubmissionResponse.SubmissionContent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * TR-02 제출 현황 조회. <b>6종 상태 판정이 여기 한 곳에만 있다.</b>
 *
 * <p>판정을 서비스에 둔 이유는 SQL에 두면 같은 규칙이 {@code trainee_home_round_view}의
 * {@code representative_status}와 두 곳에서 각각 자라기 때문이다. 뷰는 회차 카드용이고 이쪽은 제출
 * 폼용이라 쓰는 값이 다르지만, 겹치는 축(마감·분석·세션)의 해석은 같아야 한다.
 */
@Service
@RequiredArgsConstructor
public class MySubmissionService {

	private static final String GITHUB_URL = "GITHUB_URL";

	private final MySubmissionQueryRepository mySubmissionQueryRepository;

	/**
	 * @throws SubmissionException 호출자가 그 프로젝트의 유효 팀 구성원이 아니거나 회차가 없을 때.
	 *                             셋을 구분하지 않는다 — 남의 프로젝트 존재 여부를 응답으로 알려주지 않는다
	 */
	@Transactional(readOnly = true)
	public MySubmissionResponse getMySubmission(UUID projectId, UUID userId) {
		MySubmissionRow row = mySubmissionQueryRepository.findMySubmission(projectId, userId)
				.orElseThrow(() -> new SubmissionException(SubmissionErrorCode.SUBMISSION_ROUND_NOT_ACCESSIBLE));

		String status = judge(row);
		boolean failed = "ANALYSIS_FAILED".equals(status);
		boolean verifiable = "READY".equals(status) || "LOCKED".equals(status);

		return new MySubmissionResponse(
				row.assessmentRoundId().toString(),
				row.roundName(),
				row.submissionDueAt(),
				status,
				row.submissionId() == null ? null : row.submissionId().toString(),
				row.submissionMethod(),
				row.submittedAt(),
				row.analyzedAt(),
				verifiable ? row.verifyClosesAt() : null,
				failed ? failureReason(row) : null,
				failed ? row.analysisFailureCode() : null,
				contentOf(row));
	}

	/**
	 * 6종 판정. <b>순서가 규칙이다</b> — 위에서부터 먼저 맞는 것 하나로 정한다.
	 *
	 * <p>마감을 가장 먼저 보지 않는 이유: 마감이 지나도 이미 분석까지 끝났다면 화면이 그려야 할 것은
	 * "마감 지남"이 아니라 응시 안내다. {@code SUBMISSION_CLOSED}는 <b>미제출인 채로</b> 마감이
	 * 지났다는 뜻이고, 그래서 제출 유무를 먼저 가른다.
	 *
	 * <p>{@code LOCKED}를 {@code READY}보다 먼저 보는 것이 이 판정의 핵심이다. 질문이 제출된 코드로
	 * 만들어지므로 세션을 시작한 뒤 코드가 바뀌면 질문과 답이 어긋난다 — 화면이 그 사실을 문구로
	 * 설명하려면 두 상태가 갈라져 있어야 한다.
	 */
	private String judge(MySubmissionRow row) {
		boolean submitted = row.submissionId() != null;
		if (!submitted) {
			return isPastDue(row) ? "SUBMISSION_CLOSED" : "DRAFT";
		}

		// 제출 접수 자체가 깨진 경우(FETCH_FAILED·INVALID)도 화면에는 분석 실패와 같은 자리다 —
		// 둘 다 "폼을 이전 값으로 되채워 다시 제출"이 유일한 복구 경로이기 때문이다.
		if ("FETCH_FAILED".equals(row.submissionStatus()) || "INVALID".equals(row.submissionStatus())) {
			return "ANALYSIS_FAILED";
		}
		if ("FAILED".equals(row.analysisJobStatus())) {
			return "ANALYSIS_FAILED";
		}
		if (!isAnalysisComplete(row)) {
			return "ANALYZING";
		}
		return row.sessionStarted() ? "LOCKED" : "READY";
	}

	/**
	 * {@code PARTIAL}도 완료로 본다. 일부 문제 생성이 실패했을 뿐 응시는 열리며, 여기서 실패로 접으면
	 * 시작할 수 있는 세션을 두고 화면이 재제출을 권하게 된다.
	 */
	private boolean isAnalysisComplete(MySubmissionRow row) {
		return "SUCCEEDED".equals(row.analysisJobStatus()) || "PARTIAL".equals(row.analysisJobStatus());
	}

	private boolean isPastDue(MySubmissionRow row) {
		return row.submissionDueAt() != null && !row.submissionDueAt().isAfter(Instant.now());
	}

	/**
	 * 사용자에게 보일 사유. 제출 접수 실패는 {@code submission.failure_reason}에, 분석 실패는
	 * {@code analysis_job.failure_reason}이 아니라 코드에서 문구를 만든다 — 후자는 AI가 준 원문이라
	 * 학생에게 그대로 보일 문장이 아니다.
	 */
	private String failureReason(MySubmissionRow row) {
		if (row.submissionFailureReason() != null) {
			return row.submissionFailureReason();
		}
		return messageOf(row.analysisFailureCode());
	}

	/**
	 * 실패 코드 → 학생 문구. 15종 중 학생이 스스로 고칠 수 있는 것만 구체적으로 적고 나머지는 한 문장으로 접는다 —
	 * 고칠 수 없는 실패에 원인을 자세히 적으면 학생이 자기 잘못이라 읽는다.
	 */
	private String messageOf(String failureCode) {
		if (failureCode == null) {
			return "코드 분석에 실패했습니다. 매니저에게 문의해 주세요.";
		}
		return switch (failureCode) {
			case "REPO_NOT_FOUND" -> "저장소를 찾을 수 없습니다. 주소를 확인하거나 ZIP으로 제출해 주세요.";
			case "REPOSITORY_ACCESS_DENIED" -> "저장소에 접근할 수 없습니다. 비공개 저장소라면 ZIP으로 제출해 주세요.";
			case "BRANCH_NOT_FOUND" -> "입력한 브랜치를 찾을 수 없습니다. 브랜치 이름을 확인해 주세요.";
			case "INVALID_REPOSITORY_URL", "UNSUPPORTED_HOST" -> "저장소 주소 형식이 올바르지 않습니다.";
			case "EMPTY_CODE" -> "분석할 코드가 없습니다. 제출 내용을 확인해 주세요.";
			case "GIT_LOG_MISSING" -> "커밋 기록이 없습니다. `.git` 폴더를 포함해 다시 압축해 주세요.";
			case "FILE_TOO_LARGE" -> "파일이 허용 크기를 넘었습니다.";
			case "ARCHIVE_INVALID" -> "압축 파일을 열 수 없습니다.";
			case "UNSUPPORTED_LANGUAGE" -> "분석할 수 있는 언어의 코드가 없습니다.";
			default -> "코드 분석에 실패했습니다. 다시 제출하거나 매니저에게 문의해 주세요.";
		};
	}

	/**
	 * 두 수단이 같은 카드를 채운다. <b>필드는 배타적이다</b> — GitHub은 저장소·브랜치, ZIP은 파일
	 * 이름·크기이고, 커밋은 분석이 끝났으면 둘 다 갖는다.
	 *
	 * <p>브랜치는 {@code resolved_branch}(분석이 실제로 읽은 것)를 앞에 둔다. 교육생이 비워 냈으면
	 * 저장소 기본 브랜치가 답이고, 아직 분석 전이면 적어 낸 값이 답이다.
	 *
	 * <p>커밋의 원천이 수단마다 다르다. GitHub은 제출 행({@code source_commit_*})에 있고, ZIP은
	 * {@code ck_submission_method_2}가 그 컬럼을 NULL로 강제해 <b>분석 결과에서만</b> 온다
	 * ({@code code_analysis.head_commit_*}). 그래서 ZIP의 커밋 줄은 분석 성공 후에 나타난다.
	 */
	private SubmissionContent contentOf(MySubmissionRow row) {
		if (row.submissionId() == null) {
			return null;
		}
		if (GITHUB_URL.equals(row.submissionMethod())) {
			if (row.repoUrl() == null) {
				return null;
			}
			return new SubmissionContent(
					row.repoUrl(),
					firstNonBlank(row.resolvedBranch(), row.requestedBranch(), row.defaultBranch()),
					null, null,
					commitOf(row.commitSha(), row.commitMessage(), row.commitCommittedAt()));
		}
		// ZIP. 아티팩트 행이 없으면(접수 도중 등) 카드에 채울 것이 없으므로 키 자체를 빼 준다.
		if (row.artifactFileName() == null) {
			return null;
		}
		return new SubmissionContent(
				null, null,
				row.artifactFileName(),
				row.artifactFileSize(),
				commitOf(row.analysisCommitSha(), row.analysisCommitMessage(),
						row.analysisCommitCommittedAt()));
	}

	private LastCommit commitOf(String sha, String message, Instant committedAt) {
		return sha == null ? null : new LastCommit(sha, message, committedAt);
	}

	private String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}
}
