package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.member.application.CommitEmailService;
import com.bigproject.backend.domain.member.presentation.dto.CommitEmailResponse;
import com.bigproject.backend.domain.member.presentation.dto.UpdateCommitEmailRequest;
import com.bigproject.backend.global.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 태그는 Member 도메인으로 합친다. 설명은 MemberController 쪽 @Tag가 대표로 싣는다.
@Tag(name = "Member")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RestController
@RequestMapping(value = "/members/me/commit-email", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('TRAINEE')")
@RequiredArgsConstructor
public class CommitEmailController {

	private final CommitEmailService commitEmailService;

	@Operation(
			summary = "내 커밋 이메일 조회 | ✅ 사용 가능",
			description = """
					**상태**: ✅ 사용 가능

					인증 주체 본인의 커밋 이메일 등록·검증 상태를 조회합니다. 경로에 사용자 식별자가 없으며
					스코프는 액세스 토큰에서 도출합니다.

					**요청**: 파라미터 없음

					**응답 (200)**
					- registered: 등록 여부. `commitEmail` 존재 여부에서 파생하는 값이며 DB 컬럼이 아닙니다
					- commitEmail / status / verificationMethod / verifiedAt / updatedAt

					**미등록이면 `registered=false`이고 나머지는 모두 null입니다.** DB CHECK
					(`ck_app_user_commit_email_updated_at`)가 커밋 이메일 컬럼을 all-or-nothing으로 묶어
					"주소는 있는데 상태가 없는" 부분 등록 상태가 존재할 수 없기 때문입니다.

					**화면 상단 배너 노출 조건**은 `registered === false || status !== "VERIFIED"`입니다.
					"""
	)
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "조회 성공. 미등록이면 registered=false이고 나머지 필드는 null",
					content = @Content(
							schema = @Schema(implementation = CommitEmailResponse.class),
							examples = {
									@ExampleObject(
											name = "등록됨 (검증 대기)",
											value = """
													{
													  "registered": true,
													  "commitEmail": "gildong@example.com",
													  "status": "PENDING",
													  "verificationMethod": null,
													  "verifiedAt": null,
													  "updatedAt": "2026-08-06T09:14:02Z"
													}"""
									),
									@ExampleObject(
											name = "미등록",
											value = """
													{
													  "registered": false,
													  "commitEmail": null,
													  "status": null,
													  "verificationMethod": null,
													  "verifiedAt": null,
													  "updatedAt": null
													}"""
									)
							}
					)
			),
			@ApiResponse(
					responseCode = "401",
					description = "액세스 토큰이 없거나 인증 사용자를 찾을 수 없음",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			),
			@ApiResponse(
					responseCode = "403",
					description = "호출자가 교육생(TRAINEE)이 아님",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			),
			@ApiResponse(
					responseCode = "404",
					description = "`MEMBER_NOT_FOUND` — 인증은 통과했으나 계정이 삭제되어 조회되지 않음",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "MEMBER_NOT_FOUND",
									value = """
											{
											  "timestamp": "2026-08-06T09:14:02Z",
											  "status": 404,
											  "error": "MEMBER_NOT_FOUND",
											  "message": "인증된 사용자의 계정을 찾을 수 없습니다."
											}"""
							)
					)
			)
	})
	@GetMapping
	public ResponseEntity<CommitEmailResponse> getMyCommitEmail() {
		return ResponseEntity.ok(commitEmailService.getMyCommitEmail());
	}

	@Operation(
			summary = "내 커밋 이메일 등록·변경 | ✅ 사용 가능",
			description = """
					**상태**: ✅ 사용 가능

					인증 주체 본인의 커밋 이메일을 등록하거나 변경합니다. 사용자당 0..1개의 단일 값이고
					등록과 변경이 같은 동작이며 같은 값을 두 번 보내도 결과가 같으므로 **멱등한 PUT**입니다.
					성공 시 201이 아니라 **200**을 반환합니다.

					**요청** (application/json)
					- commitEmail (필수, 최대 320자): 커밋에 사용하는 이메일

					**응답 (200)**: 조회 API와 같은 형태이며 `status`는 항상 `PENDING`입니다.

					⚠️ **이 API는 `PENDING` 등록까지만 담당합니다.** `commit_email_verification_method`가
					`OAUTH` / `MANAGER_CONFIRMED` 두 가지뿐이라 **교육생 자가 입력만으로는 `VERIFIED`가 될 수 없습니다.**
					검증 완료 경로는 별건입니다.

					**이미 `VERIFIED`인 상태에서 주소를 바꾸면 검증이 초기화됩니다** — `status`가 `PENDING`으로
					내려가고 `verificationMethod`·`verifiedAt`이 함께 null이 됩니다.
					"""
	)
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "등록·변경 성공. 멱등 동작이므로 최초 등록도 201이 아닌 200",
					content = @Content(
							schema = @Schema(implementation = CommitEmailResponse.class),
							examples = @ExampleObject(
									name = "등록 직후",
									value = """
											{
											  "registered": true,
											  "commitEmail": "gildong@example.com",
											  "status": "PENDING",
											  "verificationMethod": null,
											  "verifiedAt": null,
											  "updatedAt": "2026-08-06T09:14:02Z"
											}"""
							)
					)
			),
			@ApiResponse(
					responseCode = "400",
					description = "`INVALID_EMAIL_FORMAT` — 이메일 형식이 올바르지 않거나 비어 있거나 320자를 초과함",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "INVALID_EMAIL_FORMAT",
									value = """
											{
											  "timestamp": "2026-08-06T09:14:02Z",
											  "status": 400,
											  "error": "INVALID_EMAIL_FORMAT",
											  "message": "커밋 이메일 형식이 올바르지 않습니다."
											}"""
							)
					)
			),
			@ApiResponse(
					responseCode = "401",
					description = "액세스 토큰이 없거나 인증 사용자를 찾을 수 없음",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			),
			@ApiResponse(
					responseCode = "403",
					description = "호출자가 교육생(TRAINEE)이 아님",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))
			),
			@ApiResponse(
					responseCode = "404",
					description = "`MEMBER_NOT_FOUND` — 인증은 통과했으나 계정이 삭제되어 갱신 대상이 없음",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "MEMBER_NOT_FOUND",
									value = """
											{
											  "timestamp": "2026-08-06T09:14:02Z",
											  "status": 404,
											  "error": "MEMBER_NOT_FOUND",
											  "message": "인증된 사용자의 계정을 찾을 수 없습니다."
											}"""
							)
					)
			),
			@ApiResponse(
					responseCode = "409",
					description = """
							`COMMIT_EMAIL_ALREADY_USED` — 같은 기관의 다른 **활성** 계정이 이미 쓰는 커밋 이메일 \
							(`uq_app_user_commit_email_active` 위반). 탈퇴한 계정이 쓰던 값은 재사용할 수 있습니다.""",
					content = @Content(
							schema = @Schema(implementation = ErrorResponse.class),
							examples = @ExampleObject(
									name = "COMMIT_EMAIL_ALREADY_USED",
									value = """
											{
											  "timestamp": "2026-08-06T09:14:02Z",
											  "status": 409,
											  "error": "COMMIT_EMAIL_ALREADY_USED",
											  "message": "같은 기관의 다른 사용자가 이미 사용 중인 커밋 이메일입니다."
											}"""
							)
					)
			)
	})
	@PutMapping
	public ResponseEntity<CommitEmailResponse> updateMyCommitEmail(
			@Valid @RequestBody UpdateCommitEmailRequest request
	) {
		return ResponseEntity.ok(commitEmailService.updateMyCommitEmail(request));
	}
}
