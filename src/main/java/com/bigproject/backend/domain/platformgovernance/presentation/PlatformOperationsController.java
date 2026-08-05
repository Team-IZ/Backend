package com.bigproject.backend.domain.platformgovernance.presentation;

import com.bigproject.backend.domain.platformgovernance.application.PlatformOperationsService;
import com.bigproject.backend.domain.platformgovernance.presentation.dto.InviteSuperAdminRequest;
import com.bigproject.backend.domain.platformgovernance.presentation.dto.InviteSuperAdminResponse;
import com.bigproject.backend.domain.platformgovernance.presentation.dto.PlatformModelSettingResponse;
import com.bigproject.backend.domain.platformgovernance.presentation.dto.SuperAdminListResponse;
import com.bigproject.backend.domain.platformgovernance.presentation.dto.UpdateGradingModelRequest;
import com.bigproject.backend.domain.platformgovernance.presentation.dto.UpdateModelPricingRequest;
import com.bigproject.backend.domain.platformgovernance.presentation.dto.UpdateTierModelRequest;
import com.bigproject.backend.domain.platformgovernance.presentation.dto.UpdateOperatorStatusForPlatformRequest;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(
		name = "Platform Governance",
		description = "플랫폼 전역 AI 모델 정책, 모델 티어, 단가, 보정 버전, 전역 설정 API (v2 IA: SA-03 플랫폼 설정 — 모델·단가 / 슈퍼어드민 계정)"
)
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Validated
@RestController
@RequestMapping("/platform/operations")
@RequiredArgsConstructor
public class PlatformOperationsController {

	private static final String REQUEST_ID_HEADER = "X-Request-Id";

	private final PlatformOperationsService platformOperationsService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(
			summary = "플랫폼 모델·단가 설정 조회",
			description = """
					**상태**: ✅ 사용 가능

					목업 SA-03 ① `모델 · 단가` 탭 전체를 채운다.

					**응답**
					- gradingPolicy: 채점 모델 정책(전 기관 공통). 활성 캘리브레이션 버전과 진행 중 버전을 함께 준다.
					- tierMappings: 기능(코드 세션) × 티어3 → 실제 모델 매핑. \
					기관은 티어 이름만 고르고 실제 모델은 이 매핑이 정한다.
					- modelPricings: 모델별 100만 토큰당 단가. **단가 미설정 모델도 포함**되며 `pricingMissing=true`다.

					`gradingPolicy`가 null이면 플랫폼 초기 설정이 아직 안 된 상태다.
					"""
	)
	@GetMapping("/model-settings")
	public ResponseEntity<PlatformModelSettingResponse> findModelSettings() {
		return ResponseEntity.ok(platformOperationsService.findModelSettings());
	}

	@Operation(
			summary = "채점 모델 변경 (전 기관 재캘리브레이션 유발)",
			description = """
					**상태**: ✅ 사용 가능

					목업 SA-03 ① `채점 · 고정 · claude-x` 행의 변경 액션이다.

					⚠️ **되돌릴 수 없다.** 목업: "채점 모델을 바꾸면 전 기관 재캘리브레이션이 필요하고, \
					버전이 다른 결과끼리는 화면에서 비교를 막는다."

					이 API는 한 트랜잭션에서 다음을 수행한다.
					1. 현재 활성 채점 정책을 SUPERSEDED로 닫고 새 버전 발급
					2. 새 캘리브레이션 버전 생성(PENDING)
					3. **삭제되지 않은 전 기관**에 대해 재캘리브레이션 대기 행 생성

					**요청**
					- `acknowledgeRecalibration`을 반드시 `true`로 보내야 한다. \
					화면 확인 모달을 우회한 호출을 막는 안전장치다.
					- `calibrationVersionCode`는 전체 UNIQUE이며 결과 비교에서 버전을 식별하는 값이다.

					**거절 조건**
					- 재캘리브레이션이 이미 진행 중이면 409 `CALIBRATION_IN_PROGRESS` — \
					두 버전이 동시에 돌면 어느 쪽이 비교 기준인지 알 수 없어진다.
					- 비활성(INACTIVE) 모델이면 400 `AI_MODEL_NOT_AVAILABLE`

					⚠️ 실제 재캘리브레이션 실행은 별도 배치 담당이다. 이 API는 대상과 버전을 확정하는 것까지 한다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "변경 성공(재캘리브레이션 대기 생성 완료)"),
			@ApiResponse(responseCode = "400", description = "AI_MODEL_NOT_AVAILABLE · 확인 플래그 누락"),
			@ApiResponse(responseCode = "409", description = "CALIBRATION_IN_PROGRESS · CALIBRATION_VERSION_CODE_TAKEN")
	})
	@PutMapping("/grading-model")
	public ResponseEntity<PlatformModelSettingResponse> updateGradingModel(
			@Valid @RequestBody UpdateGradingModelRequest request
	) {
		return ResponseEntity.ok(platformOperationsService.updateGradingModel(
				request, currentUserResolver.resolveCurrentMemberId()
		));
	}

	@Operation(
			summary = "티어 ↔ 모델 매핑 변경",
			description = """
					**상태**: ✅ 사용 가능

					목업 SA-03 ① `코드 세션 · 정확도 우선 / 균형 / 비용 우선` 3티어 매핑을 바꾼다.

					**요청**
					- featureCode (필수): v07 기준 티어 선택 대상은 `CODE_SESSION` 하나뿐이다
					- tierCode (필수): ACCURACY_FIRST / BALANCED / COST_FIRST
					- modelId (필수): 그 (기능, 티어)가 실제로 호출할 모델. INACTIVE 모델이면 400
					- changeReason (선택): 변경 사유

					**응답**
					- 변경 후의 플랫폼 모델·단가 설정 전체(조회 API와 같은 형태라 화면을 그대로 다시 그릴 수 있다)

					채점 모델과 달리 **재캘리브레이션이 발생하지 않는다** — 코드 세션은 점수가 아니라 \
					산출물이라 버전 간 비교 문제가 없다.

					(기능, 티어) 조합별로 버전이 올라가며, 이전 활성 버전은 SUPERSEDED로 닫힌다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "변경 성공"),
			@ApiResponse(responseCode = "400", description = "AI_MODEL_NOT_AVAILABLE")
	})
	@PutMapping("/tier-models")
	public ResponseEntity<PlatformModelSettingResponse> updateTierModel(
			@Valid @RequestBody UpdateTierModelRequest request
	) {
		return ResponseEntity.ok(platformOperationsService.updateTierModel(
				request, currentUserResolver.resolveCurrentMemberId()
		));
	}

	@Operation(
			summary = "모델 단가 수정",
			description = """
					**상태**: ✅ 사용 가능

					목업 SA-03 ① `단가 · 모델별 입력·출력 토큰 단가`.

					단가는 **100만 토큰당** 값으로 주고받는다. DB는 기준 토큰 수(`price_unit_token_count`)당 \
					단가를 저장하므로 서버가 환산한다 — 공급자가 다른 기준으로 고지하면 \
					`priceUnitTokenCount`로 지정할 수 있다.

					**단가 미설정으로 되돌리려면** 입력·출력 단가를 모두 `null`로 보낸다. \
					⚠️ **0을 넣지 마세요** — 0은 '무료'라는 뜻이고 미설정과 다르다. \
					미설정 모델의 호출은 사용량 집계에서 비용 합계에 더하지 않고 `단가 미설정`으로 따로 센다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "수정 성공"),
			@ApiResponse(responseCode = "400", description = "AI_MODEL_NOT_AVAILABLE · 단가 쌍 불일치")
	})
	@PutMapping("/models/{modelId}/pricing")
	public ResponseEntity<PlatformModelSettingResponse> updateModelPricing(
			@PathVariable UUID modelId,
			@Valid @RequestBody UpdateModelPricingRequest request
	) {
		return ResponseEntity.ok(platformOperationsService.updateModelPricing(
				modelId, request, currentUserResolver.resolveCurrentMemberId()
		));
	}

	@Operation(
			summary = "슈퍼어드민 계정 목록",
			description = """
					**상태**: ✅ 사용 가능

					목업 SA-03 ② `슈퍼어드민 계정` 탭의 목록이다.

					**응답**
					- activeCount: 활성 슈퍼어드민 수
					- 각 계정의 `deactivatable`: 정지 가능 여부. 활성 1명뿐이면 그 계정은 `false`다 \
					(목업: "마지막 한 명은 정지할 수 없다").

					초대만 되고 아직 활성화 전인 계정은 `status=PENDING`(목업 `초대됨`)이고 `name`이 null이다.
					"""
	)
	@GetMapping("/super-admins")
	public ResponseEntity<SuperAdminListResponse> findSuperAdmins() {
		return ResponseEntity.ok(platformOperationsService.findSuperAdmins());
	}

	@Operation(
			summary = "슈퍼어드민 초대",
			description = """
					**상태**: ✅ 사용 가능

					목업 SA-03 ② `+ 계정 초대`. 계정 자리를 만들고 초대 메일을 보낸다.

					**요청은 이메일 하나뿐이다.** 역할을 고르는 칸이 없다 — 이 화면에서 보내는 초대는 \
					슈퍼어드민 하나뿐이라 초대하는 화면이 곧 역할이다. 기관·기수·반도 없다 — \
					슈퍼어드민은 어느 기관에도 속하지 않는다(`app_user.org_id IS NULL`).

					**이메일 도메인 제한이 없다.** 플랫폼 도메인을 저장하는 곳이 없고, 오퍼레이터 초대와 \
					같은 이유(초대 시점에는 그 도메인 메일함을 아직 가질 수 없다)가 적용된다.

					**응답**
					- 생성된 계정 자리(memberId)와 PENDING 상태. 받는 사람은 초대 메일 링크에서 \
					이름·비밀번호만 정하면 활성화된다(`POST /auth/manager-signup`).
					- accounts: 초대 반영 후의 슈퍼어드민 목록 전체. 표를 그대로 다시 그릴 수 있다.

					**오류**
					- 403: 슈퍼어드민이 아닌 호출자
					- 409: 이미 등록되었거나 초대된 이메일
					- 502: 초대 메일 발송 실패. ⚠ 현재는 이때 **계정 자리도 롤백된다** — \
					목업이 요구하는 "자리는 남기고 링크만 실패" 동작은 오퍼레이터 초대와 함께 별도 작업으로 분리했다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "슈퍼어드민 계정 자리 생성 및 초대 발송 성공"),
			@ApiResponse(responseCode = "400", description = "이메일 형식이 올바르지 않음"),
			@ApiResponse(responseCode = "403", description = "슈퍼어드민만 슈퍼어드민을 초대할 수 있음"),
			@ApiResponse(responseCode = "409", description = "ALREADY_INVITED · 이미 등록되었거나 초대된 이메일"),
			@ApiResponse(responseCode = "502", description = "INVITE_MAIL_FAILED · 초대 메일 발송 실패")
	})
	@PostMapping("/super-admins/invitations")
	public ResponseEntity<InviteSuperAdminResponse> inviteSuperAdmin(
			@Valid @RequestBody InviteSuperAdminRequest request,
			@Parameter(description = "초대 요청 추적용 식별자이며 생략 시 서버가 생성합니다.", example = "invite-sa-001")
			@RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
			@Parameter(hidden = true) Authentication authentication
	) {
		return ResponseEntity.status(HttpStatus.CREATED).body(
				platformOperationsService.inviteSuperAdmin(request, authentication.getName(), requestId)
		);
	}

	@Operation(
			summary = "슈퍼어드민 정지 · 재활성",
			description = """
					**상태**: ✅ 사용 가능

					목업 SA-03 ② 행별 액션. ACTIVE(재활성) 또는 INACTIVE(정지)만 지정할 수 있다.

					**마지막 활성 슈퍼어드민은 정지할 수 없다**(409 `LAST_SUPER_ADMIN`). \
					정지하면 플랫폼에 들어갈 사람이 아무도 없어지고, 오퍼레이터와 달리 \
					풀어 줄 상위 권한이 아예 없어 복구가 불가능하다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "변경 성공"),
			@ApiResponse(responseCode = "404", description = "SUPER_ADMIN_NOT_FOUND"),
			@ApiResponse(responseCode = "409", description = "LAST_SUPER_ADMIN · 마지막 슈퍼어드민은 정지 불가")
	})
	@PatchMapping("/super-admins/{memberId}/status")
	public ResponseEntity<SuperAdminListResponse> updateSuperAdminStatus(
			@PathVariable UUID memberId,
			@Valid @RequestBody UpdateOperatorStatusForPlatformRequest request
	) {
		return ResponseEntity.ok(platformOperationsService.updateSuperAdminStatus(memberId, request.status()));
	}
}
