package com.bigproject.backend.domain.submission.presentation;

import com.bigproject.backend.domain.submission.application.SubmissionService;
import com.bigproject.backend.domain.submission.domain.IdempotencyKey;
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

	/**
	 * 멱등키 헤더. 다른 API가 쓰는 {@code X-Request-Id}와 <b>일부러 이름을 나눴다</b> —
	 * 그쪽은 요청마다 새로 만드는 추적용 값이고, 멱등키는 재시도해도 같아야 하는 정반대 성격이라
	 * 한 헤더로 겸하면 프록시가 {@code X-Request-Id}를 덮어쓸 때 멱등성이 조용히 깨진다.
	 */
	private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	private static final String IDEMPOTENCY_KEY_DESCRIPTION = """
			멱등키(UUID, 필수). **제출 버튼을 누른 순간 하나 만들어** 그 제출이 끝날 때까지 보관한다.

			- 타임아웃·5xx·네트워크 오류로 **재시도할 때는 같은 값**을 그대로 다시 보낸다 → 서버가 최초 결과를 반환한다
			- 사용자가 입력을 고쳐 **다시 제출하면 새 값**을 만든다 (재제출은 별개의 제출이다)
			- 같은 키를 **다른 회차**에 재사용하면 `409 IDEMPOTENCY_KEY_CONFLICT`로 거절한다
			- 서버가 대신 만들어 주지 않는다. 생략하면 `400`이다 — 임의 값을 채우면 멱등 판정이
			  항상 실패하는데 그 사실이 클라이언트에게 보이지 않기 때문이다""";

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

					동기 처리가 짧아도 네트워크 재시도로 중복 제출이 생길 수 있다. `uq_submission_current`는
					이를 막지 못하므로(두 번째 제출이 첫 번째를 supersede할 뿐이다) `Idempotency-Key`가 필수다.

					ZIP 업로드는 같은 리소스를 만들지만 `POST /submissions/zip`으로 분리돼 있다 — 이유는 그쪽
					설명 참조.""")
	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<SubmissionResponse> submitGithubUrl(
			@Valid @RequestBody CreateGithubSubmissionRequest request,
			@Parameter(description = IDEMPOTENCY_KEY_DESCRIPTION, required = true)
			@RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey
	) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(submissionService.submitGithubUrl(userId, request, IdempotencyKey.parse(idempotencyKey)));
	}

	@Operation(
			summary = "ZIP 업로드 제출·재제출",
			description = """
					GitHub URL 제출과 같은 리소스를 만드는 다른 표현이지만 **경로를 분리한다.** OpenAPI는
					경로·메서드당 operation이 하나뿐이라, 한 경로에 `consumes`만 다른 핸들러를 둘 두면 springdoc이
					둘을 한 operation으로 병합한다. 그러면 Swagger UI에서 `application/json`을 골라도 multipart
					입력 폼이 뜨고, ZIP 전용 쿼리 파라미터가 JSON 쪽에도 필수로 붙는다.

					현재 제출 교체(`is_current`) 로직은 컨트롤러가 아니라 서비스에 있으므로 경로를 나눠도
					중복 구현이 생기지 않는다.

					**접수는 `VALIDATING`으로 끝난다.** 이번 범위에서 판정하는 것은 크기와 압축 형식뿐이고,
					`EMPTY_CODE`·`GIT_LOG_MISSING` 같은 내용 판정과 안전 추출은 아직 붙지 않았다. 접수를 먼저
					확정해두는 이유는 마감 직전 후속 처리 장애로 제출이 거부되어 교육생이 마감을 놓치는 사고를
					막기 위해서다.""")
	@PostMapping(path = "/zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<SubmissionResponse> submitZip(
			@RequestParam @NotNull UUID assessmentRoundId,
			@RequestPart("file") MultipartFile file,
			@Parameter(description = IDEMPOTENCY_KEY_DESCRIPTION, required = true)
			@RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey
	) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(submissionService.submitZip(
				userId, assessmentRoundId, file, IdempotencyKey.parse(idempotencyKey)));
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
