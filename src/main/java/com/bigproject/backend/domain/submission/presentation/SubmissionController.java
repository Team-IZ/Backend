package com.bigproject.backend.domain.submission.presentation;

import com.bigproject.backend.domain.submission.application.SubmissionService;
import com.bigproject.backend.domain.submission.presentation.dto.CreateGithubSubmissionRequest;
import com.bigproject.backend.domain.submission.presentation.dto.SubmissionAnalysisResponse;
import com.bigproject.backend.domain.submission.presentation.dto.SubmissionResponse;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Tag(name = "Submission", description = "교육생 코드 제출과 코드 분석 상태 조회 API")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('TRAINEE')")
@Validated
@RestController
@RequestMapping("/submissions")
@RequiredArgsConstructor
public class SubmissionController {

	private static final String REQUEST_ID_HEADER = "X-Request-Id";

	private final SubmissionService submissionService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(
			summary = "GitHub 저장소 URL 제출·재제출",
			description = """
					**제출 시점에 백엔드는 GitHub에 접근하지 않는다.** 검사하는 것은 URL 형식과 호스트뿐이고,
					저장소가 실제로 존재하는지·접근 가능한지는 회차 마감 후 분석 단계에서 판정된다. 따라서
					형식만 맞으면 즉시 `ACCEPTED`로 접수되며, `REPO_NOT_FOUND` 같은 사유는 이 API가 아니라
					`GET /submissions/{submissionId}/analysis`의 `failureCode`로 드러난다.

					제출은 **팀 단위**다. 팀원 누구나 제출할 수 있고, 마감 전이라면 다른 팀원이 재제출할 수도 있다.
					재제출은 기존 행 수정이 아니라 새 행 생성이며 직전 제출을 `supersedesSubmissionId`로 가리킨다.

					동기 처리가 짧아도 네트워크 재시도로 중복 제출이 생길 수 있으므로 `X-Request-Id`를 보내면
					같은 값의 재요청은 최초 결과를 그대로 돌려준다.""")
	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<SubmissionResponse> submitGithubUrl(
			@Valid @RequestBody CreateGithubSubmissionRequest request,
			@Parameter(description = "멱등키. 생략하면 서버가 생성하며 재시도 보호를 받지 못한다.")
			@RequestHeader(value = REQUEST_ID_HEADER, required = false) UUID requestId
	) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		UUID idempotencyKey = requestId == null ? UUID.randomUUID() : requestId;
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(submissionService.submitGithubUrl(userId, request, idempotencyKey));
	}

	@Operation(
			summary = "ZIP 업로드 제출·재제출",
			description = """
					3번(GitHub URL)과 같은 리소스를 만드는 다른 표현이라 경로를 나누지 않고 `Content-Type`으로
					분기한다. 경로를 나누면 "GitHub로 제출한 뒤 ZIP으로 재제출" 같은 교차 케이스에서 현재 제출
					교체 로직을 두 곳에 중복 구현하게 된다.

					**접수는 `VALIDATING`으로 끝난다.** 이번 범위에서 판정하는 것은 크기와 압축 형식뿐이고,
					`EMPTY_CODE`·`GIT_LOG_MISSING` 같은 내용 판정과 안전 추출은 아직 붙지 않았다. 접수를 먼저
					확정해두는 이유는 마감 직전 후속 처리 장애로 제출이 거부되어 교육생이 마감을 놓치는 사고를
					막기 위해서다.""")
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<SubmissionResponse> submitZip(
			@RequestParam @NotNull UUID assessmentRoundId,
			@RequestPart("file") MultipartFile file
	) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		return ResponseEntity.status(HttpStatus.ACCEPTED)
				.body(submissionService.submitZip(userId, assessmentRoundId, file));
	}

	@Operation(
			summary = "코드 분석 진행 상태·실패 사유 조회",
			description = """
					폴링 대상은 `code_analysis`가 아니라 `analysis_job`이다. 전자는 성공했을 때에만 생기는
					결과물이라 "진행 중"과 "분석 없음"을 구분할 수 없고 실패 사유도 갖지 않는다.

					**분석 실행 API는 교육생에게 제공하지 않는다.** 분석은 회차 마감 후 팀당 1회 배치로 실행되므로,
					마감 전 조회는 정상적으로 `phase=NOT_STARTED`를 반환한다.

					제출은 팀 단위라 같은 팀이면 누가 조회해도 같은 결과가 나온다.""")
	@GetMapping("/{submissionId}/analysis")
	public ResponseEntity<SubmissionAnalysisResponse> getAnalysis(@PathVariable UUID submissionId) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		return ResponseEntity.ok(submissionService.getAnalysis(userId, submissionId));
	}
}
