package com.bigproject.backend.domain.submission.presentation;

import com.bigproject.backend.domain.submission.application.SubmissionService;
import com.bigproject.backend.domain.submission.domain.IdempotencyKey;
import com.bigproject.backend.domain.submission.presentation.dto.CreateGithubSubmissionRequest;
import com.bigproject.backend.domain.submission.presentation.dto.RepositoryCheckRequest;
import com.bigproject.backend.domain.submission.presentation.dto.RepositoryCheckResponse;
import com.bigproject.backend.domain.submission.presentation.dto.SubmissionAnalysisResponse;
import com.bigproject.backend.domain.submission.presentation.dto.SubmissionAnalysisResultResponse;
import com.bigproject.backend.domain.submission.presentation.dto.SubmissionResponse;
import com.bigproject.backend.global.exception.ErrorResponse;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
@RequestMapping(value = "/submissions", produces = MediaType.APPLICATION_JSON_VALUE)
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
			operationId = "submitGithubUrl",
			summary = "GitHub 저장소 URL 제출·재제출 | ✅ 사용 가능",
			description = """
					**제출 → 분석 → 세션까지 끝까지 간다.** 접수 직후 트리거되는 코드 분석은 AI 원본 서버
					(`ai.origin-base-url`)의 `POST /analyses`로 나가며 저장소 주소와 브랜치를 함께 싣는다 —
					clone·분석의 주체는 AI 서버이지만 그쪽으로 주소를 넘기는 경로는 백엔드에 있다.

					접수 전에 **AI 프록시를 먼저 깨운다**(`GET /api/health`). 원본은 PAUSED 상태를 스스로 깨우지
					못하고, 프록시가 원본이 RUNNING이 될 때까지 동기로 기다린다(유휴 후 첫 호출 80초 안팎,
					상한 `ai.proxy.warm-up-timeout` 기본 150초). 깨우지 못하면 접수 자체를 `AI_SERVER_UNAVAILABLE`로
					거절한다 — 접수만 받아 두면 실패가 한참 뒤 분석 화면에서야 드러나고 그때는 마감이 지나 있다.
					ZIP 업로드도 같은 순서를 탄다.

					**제출 시점에 백엔드는 GitHub에 접근하지 않는다.** 검사하는 것은 URL 형식과 호스트뿐이고,
					저장소가 실제로 존재하는지·접근 가능한지는 분석 단계에서 판정된다. 따라서 형식만 맞으면 즉시
					`ACCEPTED`로 접수되며, `REPO_NOT_FOUND` 같은 사유는 이 API가 아니라
					`GET /submissions/{submissionId}/analysis`의 `failureCode`로 드러난다.

					제출은 **팀 단위**다. 팀원 누구나 제출할 수 있고, 마감 전이라면 다른 팀원이 재제출할 수도 있다.
					재제출은 기존 행 수정이 아니라 새 행 생성이며 직전 제출을 `supersedesSubmissionId`로 가리킨다.

					## 요청 (헤더)

					| 헤더 | 필수 | 타입 | 설명 |
					| --- | --- | --- | --- |
					| `Idempotency-Key` | 필수 | UUID | 재시도로 인한 중복 제출을 막는다. 생략하면 `400`. 상세는 아래 참고 |

					동기 처리가 짧아도 네트워크 재시도로 중복 제출이 생길 수 있다. `uq_submission_current`는
					이를 막지 못하므로(두 번째 제출이 첫 번째를 supersede할 뿐이다) 이 헤더가 필수다.

					## 요청 (본문)

					| 필드 | 필수 | 타입 | 설명 |
					| --- | --- | --- | --- |
					| `assessmentRoundId` | 필수 | UUID | 제출 대상 회차 |
					| `repositoryUrl` | 필수 | string | 저장소 주소 원문. 최대 2000자. GitHub 호스트만 허용 |
					| `branch` | 선택 | string | 분석할 브랜치. 최대 255자. 비우면 AI가 기본 브랜치를 골라 `resolvedBranch`로 회신 |

					## 응답 (201)

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `submissionId` | UUID | 제출 식별자. 이후 분석 조회에 쓴다 |
					| `method` | enum | `GITHUB_URL` |
					| `status` | enum | 접수 즉시 `ACCEPTED`. 내용 판정은 분석 단계의 사건이다 |
					| `submittedAt` | datetime | 마감 판정의 기준 시각 (ISO-8601 UTC) |
					| `current` | boolean | 팀·회차의 현재 제출인지 여부 |
					| `supersedesSubmissionId` | UUID? | 직전 제출. 첫 제출이면 `null` |
					| `repositoryVerificationId` | UUID? | 저장소 확인 실행. 분석 시점에 생기므로 접수 시점에는 `null` |
					| `artifactId` | UUID? | ZIP 전용. GitHub 제출은 항상 `null` |

					## 오류

					| 코드 | 상태 | 언제 |
					| --- | --- | --- |
					| `IDEMPOTENCY_KEY_REQUIRED` | 400 | 헤더를 보내지 않았다 |
					| `IDEMPOTENCY_KEY_INVALID` | 400 | 헤더가 UUID가 아니다 |
					| `INVALID_REPOSITORY_URL` | 400 | 주소 형식이 올바르지 않다 |
					| `UNSUPPORTED_HOST` | 400 | GitHub이 아닌 호스트다 |
					| `SUBMISSION_ROUND_NOT_ACCESSIBLE` | 404 | 이 교육생이 제출할 수 있는 회차가 아니다 |
					| `SUBMISSION_ROUND_NOT_OPEN` | 409 | 회차가 `OPEN`이 아니다 |
					| `SUBMISSION_DEADLINE_PASSED` | 409 | 마감이 지났다 |
					| `SUBMISSION_METHOD_NOT_ALLOWED` | 409 | 기관 정책이 GitHub 제출을 막았다 |
					| `IDEMPOTENCY_KEY_CONFLICT` | 409 | 같은 키를 다른 회차에 재사용했다 |
					| `AI_SERVER_UNAVAILABLE` | 503 | AI 프록시를 깨우지 못했다. **재시도하면 된다** |

					ZIP 업로드는 같은 리소스를 만들지만 `POST /submissions/zip`으로 분리돼 있다.

					## 🔴 성공 몸이 세 가지다 — 셋 다 `201`이다

					| 상황 | `supersedesSubmissionId` | `submissionId` |
					|---|---|---|
					| 첫 제출 | `null` | 새 값 |
					| 재제출 | **직전 제출 ID** | 새 값 |
					| 멱등 재시도(같은 키·같은 내용) | 최초 결과 그대로 | **최초와 같은 값** |

					세 번째가 있어서 클라이언트는 **`201`을 "새로 만들어졌다"로 읽으면 안 된다.** 같은 멱등키로
					재시도하면 새 행을 만들지 않고 최초 결과를 그대로 돌려주므로, 화면이 제출 횟수를 세고 있다면
					응답의 `submissionId`로 중복을 걸러야 한다.

					`null`인 필드는 키가 빠지지 않고 `null`로 온다 — 세션 API와 직렬화 규칙이 다르다.""")
	// 접수는 201이다. 선언하지 않으면 springdoc 이 기본값 200 으로 적어, 스펙과 서버가 서로 다른
	// 상태 코드를 말하게 된다(23차 R4에서 오류 응답과 함께 드러났다).
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "제출 접수됨. 첫 제출·재제출·멱등 재시도가 모두 이 상태다",
					content = @Content(
							schema = @Schema(implementation = SubmissionResponse.class),
							examples = {
									@ExampleObject(
											name = "첫 제출",
											value = """
													{
													  "submissionId": "7d3c8a15-6e29-4b70-9c81-2f5a4d0b6e37",
													  "method": "GITHUB_URL",
													  "status": "ACCEPTED",
													  "submittedAt": "2026-08-15T04:02:11Z",
													  "current": true,
													  "supersedesSubmissionId": null,
													  "repositoryVerificationId": null,
													  "artifactId": null
													}"""),
									@ExampleObject(
											name = "재제출 (직전 제출을 supersede)",
											description = "기존 행 수정이 아니라 새 행이다. 직전 제출은 current=false가 된다",
											value = """
													{
													  "submissionId": "b1f4e2d9-30a7-4c68-85be-9d1c7a3f6042",
													  "method": "GITHUB_URL",
													  "status": "ACCEPTED",
													  "submittedAt": "2026-08-15T06:41:55Z",
													  "current": true,
													  "supersedesSubmissionId": "7d3c8a15-6e29-4b70-9c81-2f5a4d0b6e37",
													  "repositoryVerificationId": null,
													  "artifactId": null
													}"""),
									@ExampleObject(
											name = "멱등 재시도 (같은 Idempotency-Key)",
											description = "새 행을 만들지 않는다. submissionId와 submittedAt이 최초 제출 그대로다",
											value = """
													{
													  "submissionId": "7d3c8a15-6e29-4b70-9c81-2f5a4d0b6e37",
													  "method": "GITHUB_URL",
													  "status": "ACCEPTED",
													  "submittedAt": "2026-08-15T04:02:11Z",
													  "current": true,
													  "supersedesSubmissionId": null,
													  "repositoryVerificationId": null,
													  "artifactId": null
													}""")
							})),
			@ApiResponse(responseCode = "400",
					description = "IDEMPOTENCY_KEY_REQUIRED · IDEMPOTENCY_KEY_INVALID · INVALID_REPOSITORY_URL · UNSUPPORTED_HOST · VALIDATION_FAILED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "SUBMISSION_ROUND_NOT_ACCESSIBLE",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409",
					description = "SUBMISSION_ROUND_NOT_OPEN · SUBMISSION_DEADLINE_PASSED · SUBMISSION_METHOD_NOT_ALLOWED · IDEMPOTENCY_KEY_CONFLICT",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "503",
					description = "AI_SERVER_UNAVAILABLE — **재시도하면 된다.** 같은 멱등키를 그대로 쓴다",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
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
			operationId = "submitZip",
			summary = "ZIP 업로드 제출·재제출 | ✅ 사용 가능",
			description = """
					GitHub URL 제출과 같은 리소스를 만드는 다른 표현이지만 **경로를 분리한다.** OpenAPI는
					경로·메서드당 operation이 하나뿐이라, 한 경로에 `consumes`만 다른 핸들러를 둘 두면 springdoc이
					둘을 한 operation으로 병합한다. 그러면 Swagger UI에서 `application/json`을 골라도 multipart
					입력 폼이 뜨고, ZIP 전용 쿼리 파라미터가 JSON 쪽에도 필수로 붙는다.

					**접수는 `ACCEPTED`로 끝난다.** 백엔드가 보는 것은 크기와 압축 형식뿐이고,
					`EMPTY_CODE`·`GIT_LOG_MISSING` 같은 내용 판정은 분석 단계에서 AI가 `failureCode`로
					돌려준다. 접수 즉시 분석이 트리거된다.

					## 요청 (헤더)

					| 헤더 | 필수 | 타입 | 설명 |
					| --- | --- | --- | --- |
					| `Idempotency-Key` | 필수 | UUID | 재시도로 인한 중복 업로드를 막는다. 생략하면 `400` |

					## 요청 (쿼리 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					| --- | --- | --- | --- |
					| `assessmentRoundId` | 필수 | UUID | 제출 대상 회차 |

					## 요청 (multipart/form-data)

					| 파트 | 필수 | 타입 | 설명 |
					| --- | --- | --- | --- |
					| `file` | 필수 | binary | git log를 포함한 ZIP. **상한 50MB** |

					### 🔴 상한은 정확히 50MB = 52,428,800 바이트

					**화면 상한과 서버 상한이 같아야 한다** — 다르면 "올린 뒤에 거절"이 생긴다.
					서버 값은 `app.submission.max-zip-bytes`(기본 `52428800`)이고, 이 값을 넘으면
					`413 FILE_TOO_LARGE`다.

					그 앞에 톰캣 상한이 하나 더 있다(`spring.servlet.multipart.max-file-size`, 기본 `60MB`).
					**일부러 넉넉하게 잡아 둔 것**이라, 50~60MB 파일은 톰캣을 통과한 뒤 컨트롤러에서
					`413 FILE_TOO_LARGE`로 거절된다 — 톰캣이 먼저 끊으면 우리 에러 코드가 실리지 않아
					화면이 사유를 알 수 없기 때문이다.

					## 응답 (202)

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `submissionId` | UUID | 제출 식별자. 이후 분석 조회에 쓴다 |
					| `method` | enum | `ZIP_WITH_GITLOG` |
					| `status` | enum | 접수 즉시 `ACCEPTED` |
					| `submittedAt` | datetime | 업로드 접수 시각 (ISO-8601 UTC) |
					| `current` | boolean | 팀·회차의 현재 제출인지 여부 |
					| `supersedesSubmissionId` | UUID? | 직전 제출. 첫 제출이면 `null` |
					| `repositoryVerificationId` | UUID? | GitHub 전용. ZIP 제출은 항상 `null` |
					| `artifactId` | UUID? | 저장된 아티팩트 식별자 |

					## 오류

					| 코드 | 상태 | 언제 |
					| --- | --- | --- |
					| `IDEMPOTENCY_KEY_REQUIRED` | 400 | 헤더를 보내지 않았다 |
					| `IDEMPOTENCY_KEY_INVALID` | 400 | 헤더가 UUID가 아니다 |
					| `ARCHIVE_INVALID` | 400 | 빈 파일이거나 ZIP으로 열리지 않는다 |
					| `SUBMISSION_ROUND_NOT_ACCESSIBLE` | 404 | 이 교육생이 제출할 수 있는 회차가 아니다 |
					| `SUBMISSION_ROUND_NOT_OPEN` | 409 | 회차가 `OPEN`이 아니다 |
					| `SUBMISSION_DEADLINE_PASSED` | 409 | 마감이 지났다 |
					| `SUBMISSION_METHOD_NOT_ALLOWED` | 409 | `organization_policy.allow_zip_submission=FALSE`다 |
					| `IDEMPOTENCY_KEY_CONFLICT` | 409 | 같은 키를 다른 회차에 재사용했다 |
					| `FILE_TOO_LARGE` | 413 | 허용 크기를 넘었다 |
					| `ARTIFACT_STORE_FAILED` | 500 | 파일 저장에 실패했다 |
					| `AI_SERVER_UNAVAILABLE` | 503 | AI 프록시를 깨우지 못했다. **재시도하면 된다** |

					> **2026-08-09 보류 해제.** 종전에는 "AI 서버에 ZIP을 전달할 자리가 없다"는 이유로 이
					> 경로를 막아 두었으나, `POST /api/v0/analyses`에 `multipart/form-data`(`payload` +
					> `file`) 경로가 생겨 근거가 사라졌다. S3 presigned URL이 아니라 **백엔드가 파일을 직접
					> 실어 보내는** 방식이라, GitHub 제출과 달리 AI 서버에 저장소 접근 권한이 없어도 된다.

					## 🔴 성공 몸이 세 가지다 — 셋 다 `202`다

					| 상황 | `supersedesSubmissionId` | `submissionId` |
					|---|---|---|
					| 첫 제출 | `null` | 새 값 |
					| 재제출 | **직전 제출 ID** | 새 값 |
					| 멱등 재시도(같은 키) | 최초 결과 그대로 | **최초와 같은 값** |

					GitHub 제출과 다른 점은 `artifactId`가 채워지고 `repositoryVerificationId`가 항상 `null`이라는
					것뿐이다. `202`를 "새로 만들어졌다"로 읽으면 안 되는 이유도 같다 — 멱등 재시도가 같은 상태로
					최초 결과를 돌려준다.""")
	@ApiResponses({
			@ApiResponse(
					responseCode = "202",
					description = "업로드 접수됨. 분석은 비동기로 이어진다",
					content = @Content(
							schema = @Schema(implementation = SubmissionResponse.class),
							examples = {
									@ExampleObject(
											name = "첫 업로드",
											value = """
													{
													  "submissionId": "5a2b9c74-1de3-4f86-90a5-6c8e3b7d2f19",
													  "method": "ZIP_WITH_GITLOG",
													  "status": "ACCEPTED",
													  "submittedAt": "2026-08-15T04:02:11Z",
													  "current": true,
													  "supersedesSubmissionId": null,
													  "repositoryVerificationId": null,
													  "artifactId": "e8f1d0a6-4b57-4c29-83de-1a7b5c9f2064"
													}"""),
									@ExampleObject(
											name = "재업로드 (직전 제출을 supersede)",
											value = """
													{
													  "submissionId": "c7e0b342-95af-4d18-a26f-3b8d1e5c7049",
													  "method": "ZIP_WITH_GITLOG",
													  "status": "ACCEPTED",
													  "submittedAt": "2026-08-15T06:41:55Z",
													  "current": true,
													  "supersedesSubmissionId": "5a2b9c74-1de3-4f86-90a5-6c8e3b7d2f19",
													  "repositoryVerificationId": null,
													  "artifactId": "9b4c6e28-7d15-403a-b8f9-2e6a1c5d8703"
													}"""),
									@ExampleObject(
											name = "멱등 재시도 (같은 Idempotency-Key)",
											description = "새 아티팩트를 저장하지 않는다. 최초 결과 그대로다",
											value = """
													{
													  "submissionId": "5a2b9c74-1de3-4f86-90a5-6c8e3b7d2f19",
													  "method": "ZIP_WITH_GITLOG",
													  "status": "ACCEPTED",
													  "submittedAt": "2026-08-15T04:02:11Z",
													  "current": true,
													  "supersedesSubmissionId": null,
													  "repositoryVerificationId": null,
													  "artifactId": "e8f1d0a6-4b57-4c29-83de-1a7b5c9f2064"
													}""")
							})),
			@ApiResponse(responseCode = "400",
					description = "IDEMPOTENCY_KEY_REQUIRED · IDEMPOTENCY_KEY_INVALID · ARCHIVE_INVALID · VALIDATION_FAILED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "SUBMISSION_ROUND_NOT_ACCESSIBLE",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409",
					description = "SUBMISSION_ROUND_NOT_OPEN · SUBMISSION_DEADLINE_PASSED · SUBMISSION_METHOD_NOT_ALLOWED · IDEMPOTENCY_KEY_CONFLICT",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "413",
					description = "FILE_TOO_LARGE — 50MB(52,428,800 바이트) 초과",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "500", description = "ARTIFACT_STORE_FAILED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "503",
					description = "AI_SERVER_UNAVAILABLE — **재시도하면 된다.** 같은 멱등키를 그대로 쓴다",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
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
			operationId = "checkRepository",
			summary = "저장소 주소 사전 확인 | ✅ 사용 가능",
			description = """
					**제출 버튼을 누르기 전에** 저장소 주소를 한 번 확인한다. 제출 시점의 형식·호스트 검사를
					그대로 미리 돌려 보는 것이라, 여기서 통과한 주소는 `POST /submissions`에서 같은 이유로
					거절되지 않는다.

					## 🔴 무엇을 확인하고 무엇을 확인하지 않는가

					| | 확인한다 | 통과 뒤에도 실패할 수 있는 것 |
					|---|---|---|
					| | 주소 형식(`scheme`·경로 깊이·허용 문자) | 저장소가 실제로 존재하는가 |
					| | 호스트가 `github.com`인가 | **비공개·조직 밖이라 접근이 막히는가** |
					| | `.git`·후행 슬래시·대소문자 정규화 | 브랜치가 있는가 |

					⚠️ **저장소 존재·접근 여부는 이 API가 답할 수 없다.** 백엔드에는 GitHub 경로가 없고
					clone·fetch 주체가 AI 서버로 확정돼 있기 때문이다(2026-08-06). 그 실패는 분석 단계에서
					`GET /submissions/{submissionId}/analysis`의 `failureCode`에 `REPO_NOT_FOUND`·
					`REPOSITORY_ACCESS_DENIED`로 나타난다.

					**그래서 "비공개 저장소는 ZIP으로" 안내는 이 응답이 아니라 분석 실패 응답에 붙어야 한다.**
					여기서 미리 막을 수 있는 것은 오타·잘못된 호스트·저장소가 아닌 주소까지다 — 실제로 가장
					흔한 부류이고, 이것만 걸러도 "제출 뒤 분석 실패로 마감을 놓치는" 경로 하나가 사라진다.

					## 요청

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `repoUrl` | string | 확인할 주소. `https://` 생략 가능 |

					**회차를 받지 않는다.** 주소 자체에 대한 판정이라 회차·팀·마감과 무관하고, 화면이
					제출 폼을 그리기 전에도 부를 수 있어야 한다.

					## 응답 (200)

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `ok` | boolean | 200에서는 **항상 `true`** |
					| `normalizedUrl` | string | 정규화 주소. 확인 문구에 그대로 쓰면 오타가 눈에 보인다 |
					| `ownerLogin` | string | 소유자(사용자·조직) |
					| `repositoryName` | string | 저장소 이름 |

					`ok: false`를 두지 않은 이유는 형식 불일치와 저장소 없음이 서로 다른 안내로 이어지기
					때문이다 — 전자는 입력을 고치면 되고 후자는 ZIP으로 갈아타야 한다. boolean 하나에 겹쳐
					담으면 화면이 두 사건을 같은 분기로 처리하게 된다.

					## 부수 효과가 없다

					행을 만들지 않고 멱등키도 쓰지 않는다. 입력 중에 여러 번 불러도 된다.

					## 오류

					| 코드 | 상태 | 언제 |
					|---|---|---|
					| `INVALID_REPOSITORY_URL` | 400 | 형식이 저장소 주소가 아니다(`/tree/main` 등 더 깊은 경로 포함) |
					| `UNSUPPORTED_HOST` | 400 | `github.com`이 아니다 |
					""")
	@PostMapping(path = "/repository-checks", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<RepositoryCheckResponse> checkRepository(
			@Valid @RequestBody RepositoryCheckRequest request
	) {
		return ResponseEntity.ok(submissionService.checkRepositoryUrl(request.repoUrl()));
	}

	@Operation(
			summary = "코드 분석 진행 상태·실패 사유 조회 | ✅ 사용 가능",
			description = """
					제출 후 화면이 초 단위로 도는 폴링 대상이다. 폴링 대상이 `code_analysis`가 아니라
					`analysis_job`인 이유는, 전자가 성공했을 때에만 생기는 결과물이라 "진행 중"과 "분석 없음"을
					구분할 수 없고 실패 사유도 갖지 않기 때문이다.

					**분석 실행 API는 교육생에게 제공하지 않는다.** 실행 주체는 제출 이벤트를 받는 배치뿐이고,
					아직 집어가기 전이라면 정상적으로 `phase=NOT_STARTED`를 반환한다.

					제출은 팀 단위라 같은 팀이면 누가 조회해도 같은 결과가 나온다. 다만 **세션 준비 여부만은
					조회자 본인 기준**이다 — 아래 `SESSION_PREPARATION_FAILED` 참고.

					## 요청 (경로 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					| --- | --- | --- | --- |
					| `submissionId` | 필수 | UUID | 조회할 제출. 같은 팀의 제출만 볼 수 있다 |

					쿼리 파라미터는 없다.

					## 응답 (200)

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `submissionId` | UUID | 조회한 제출 |
					| `phase` | enum | `NOT_STARTED` · `QUEUED` · `RUNNING` · `SUCCEEDED` · `PARTIAL` · `FAILED` |
					| `analysisJobId` | UUID? | 분석 실행. 시작 전이면 `null` |
					| `executionNo` | int? | 재시도 회차(1부터). 시작 전이면 `null` |
					| `startedAt` | datetime? | 분석 시작 시각 |
					| `completedAt` | datetime? | 분석 종료 시각 |
					| `failureCode` | string? | `FAILED`일 때만. 아래 표 참고 |
					| `failureReason` | string? | 실패 사유 원문 |
					| `codeAnalysisId` | UUID? | 성공 시 생성된 분석 결과. 그 외에는 `null` |

					### phase

					| 값 | 뜻 |
					| --- | --- |
					| `NOT_STARTED` | 배치가 아직 이 제출을 집어가지 않았다. 제출 직후의 정상 상태다 |
					| `QUEUED` | AI 서버가 요청을 접수했다 |
					| `RUNNING` | 분석 중. 실측 5분 안팎이다 |
					| `SUCCEEDED` | 분석 완료. 결과는 `.../analysis/result`로 읽는다 |
					| `PARTIAL` | 일부 개념만 문항을 만들었다. 결과는 있으므로 성공과 같게 다룬다 |
					| `FAILED` | 실패. `failureCode`로 사유를 가른다 |

					### failureCode

					분석 실행 실패 6종과 저장소·ZIP 접근 실패를 합해 15종이다. 저장소 주소 오류도 제출이 아니라
					여기로 드러난다.

					| 묶음 | 값 |
					| --- | --- |
					| 분석 실행 | `TEMPORARY_ERROR` · `ANALYSIS_TIMEOUT` · `MODEL_ERROR` · `SOURCE_UNREACHABLE` · `UNSUPPORTED_LANGUAGE` |
					| 저장소 접근 | `INVALID_REPOSITORY_URL` · `REPO_NOT_FOUND` · `REPOSITORY_ACCESS_DENIED` · `BRANCH_NOT_FOUND` · `UNSUPPORTED_HOST` |
					| ZIP 검증 | `FILE_TOO_LARGE` · `ARCHIVE_INVALID` · `EMPTY_CODE` · `PROHIBITED_FILE` · `GIT_LOG_MISSING` |

					🔴 **`SESSION_PREPARATION_FAILED`만 예외다.** `analysis_job.failure_code`에 없는 값이며
					서버가 조회 시점에 판정해 내려 준다. **분석은 성공했지만 이 교육생의 세션·문항이 준비되지
					않아 응시를 시작할 수 없다**는 뜻이다(응시 행이 없거나, 팀 배정이 끊겼거나, AI가 4축 질문·
					힌트를 온전히 주지 않아 문항이 통째로 스킵된 경우). 이때 `phase=FAILED`, `codeAnalysisId=null`로
					내려가며 원장의 job은 `SUCCEEDED`로 남는다 — 분석 자체는 실제로 성공했고 비용도 이미 나갔기
					때문이다. 화면은 재제출을 안내하면 된다.

					🔴 **`EXTERNAL_JOB_ID_LOST`도 `analysis_job.failure_code`에 없는 값이다.** 분석 행은
					아직 진행 중(`QUEUED`·`RUNNING`)인데 AI가 발급한 작업 ID가 사라져 상태를 더 따라갈 수
					없다는 뜻이며, 이때 `phase=FAILED`, `codeAnalysisId=null`로 내려간다. 서버 폴러가 1분 안에
					같은 실행을 `MODEL_ERROR`로 닫고 재시도 여지가 남아 있으면 다시 요청하므로, 폴링을 계속하면
					새 실행의 `QUEUED`가 이어질 수 있다. 화면은 재제출을 안내하면 된다.

					## 오류

					| 코드 | 상태 | 언제 |
					| --- | --- | --- |
					| `SUBMISSION_NOT_FOUND` | 404 | 그런 제출이 없다 |
					| `SUBMISSION_ACCESS_DENIED` | 403 | 다른 팀의 제출이다 |""")
	@GetMapping("/{submissionId}/analysis")
	public ResponseEntity<SubmissionAnalysisResponse> getAnalysis(@PathVariable UUID submissionId) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		return ResponseEntity.ok(submissionService.getAnalysis(userId, submissionId));
	}

	@Operation(
			summary = "코드 분석 결과 조회 | ✅ 사용 가능",
			description = """
					분석이 성공한 뒤 화면이 한 번 읽는 결과 본체다. 문제 슬롯·요구사항 판정·본인 세션을 함께 준다.

					**진행 상태 폴링은 `GET /submissions/{submissionId}/analysis`로 한다.** 둘을 나눈 이유는
					폴링이 초 단위로 도는 반면 결과는 한 번만 읽기 때문이다 — 한 응답에 합치면 "분석 중"을
					확인하는 요청마다 문제·근거를 함께 조회하게 된다.

					제출은 팀 단위라 같은 팀이면 누가 조회해도 같은 결과가 나오지만, `session`만은
					**조회자 본인의 응시**다. 응시는 개인 단위이기 때문이다.

					## 요청 (경로 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					| --- | --- | --- | --- |
					| `submissionId` | 필수 | UUID | 조회할 제출. 같은 팀의 제출만 볼 수 있다 |

					쿼리 파라미터는 없다.

					## 응답 (200)

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `submissionId` | UUID | 조회한 제출 |
					| `analysisId` | UUID | 분석 결과 식별자 |
					| `appliedScope` | string | AI가 실제로 적용한 추출 범위. 예: `TOTAL` |
					| `scopeFallback` | boolean | `true`면 요청보다 넓게 분석됐다. 개인 커밋 기준으로 볼 수 없어 경고를 띄운다 |
					| `fallbackReason` | string? | 범위가 확대된 사유 |
					| `resolvedBranch` | string? | AI가 실제로 분석한 브랜치. ZIP 제출은 `null` |
					| `headCommit` | object? | `{ commitHash, commitMessage, committedAt }` |
					| `analyzedAt` | datetime | 분석 완료 시각 (ISO-8601 UTC) |
					| `problems[]` | array | 문제 슬롯. 구조는 아래 |
					| `requirementResults[]` | array | 요구사항 P/F 판정. 구조는 아래 |
					| `session` | object? | 조회자 **본인**의 세션. 구조는 아래 |

					### problems[] 각 항목

					근거를 찾지 못한 슬롯도 `generationStatus=NOT_GENERATED`로 **함께 온다.** 화면에서
					`―`(문항 없음)로 표시할 근거이며 **0단(물어봤는데 못 풀었음)과 다르다.**

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `problemNo` | int | 문제 번호. `1`~`3` |
					| `generationStatus` | enum | `GENERATED` · `NOT_GENERATED` |
					| `notGeneratedReason` | string? | 문항을 만들지 못한 사유. `GENERATED`면 `null` |
					| `title` | string? | 문제 제목. `NOT_GENERATED`면 `null` |
					| `problemType` | string? | 문제 유형. 예: `DESIGN_CHOICE` |
					| `codeLanguage` | string? | 코드 언어 |
					| `sourcePath` | string? | 근거 파일 경로 |
					| `lineStart` / `lineEnd` | int? | 근거 라인 범위 |
					| `codeSnippet` | string? | 출제에 쓰인 코드 원문. `NOT_GENERATED` 슬롯은 `null` |

					### requirementResults[] 각 항목

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `requirementKey` | string | 요구사항 키 |
					| `title` | string | 요구사항 제목 |
					| `result` | enum | `PENDING` · `PASS` · `FAIL` |
					| `evidence` | string? | 판정 근거 요약 |
					| `judgedByAi` | boolean | AI 판정이면 `true`, 사람이 판정했으면 `false` |

					### session (object)

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `sessionId` | UUID | 응시를 시작할 때 쓰는 세션 식별자 |
					| `status` | enum | `READY` · `IN_PROGRESS` · `PAUSED` · `COMPLETED` 등 |
					| `stageCount` | int | 깔린 문제 단계 수. **문항 3개면 12**(3 × 4축)다 |

					⚠️ `session`이 `null`이거나 `stageCount`가 `GENERATED 문제 수 × 4`보다 작으면 **응시를 시작할
					수 없는 상태**다. 그 판정은 `GET /submissions/{submissionId}/analysis`가
					`SESSION_PREPARATION_FAILED`로 먼저 알려 준다.

					## 오류

					| 코드 | 상태 | 언제 |
					| --- | --- | --- |
					| `SUBMISSION_NOT_FOUND` | 404 | 그런 제출이 없다 |
					| `ANALYSIS_RESULT_NOT_FOUND` | 404 | 분석이 아직 성공하지 않았다. 진행 중인지 실패인지는 상태 조회의 `phase`로 구분한다 |
					| `SUBMISSION_ACCESS_DENIED` | 403 | 다른 팀의 제출이다 |""")
	@GetMapping("/{submissionId}/analysis/result")
	public ResponseEntity<SubmissionAnalysisResultResponse> getAnalysisResult(@PathVariable UUID submissionId) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		return ResponseEntity.ok(submissionService.getAnalysisResult(userId, submissionId));
	}
}
