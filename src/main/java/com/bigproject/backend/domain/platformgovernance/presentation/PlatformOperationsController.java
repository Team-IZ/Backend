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
			summary = "플랫폼 모델·단가 설정 조회 | ✅ 사용 가능",
			description = """
					SA-03 ① `모델 · 단가` 탭 **전체를 한 번에** 채운다. 이 화면에서 다른 조회 API 는 필요 없다.

					## 요청

					없다. 파라미터·본문 모두 필요 없다.

					## 응답 — 최상위

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `gradingPolicy` | object? | 채점 모델 정책(전 기관 공통). **`null` 이면 플랫폼 초기 설정 전** |
					| `tierMappings[]` | array | 기능 × 티어 → 모델 매핑 |
					| `modelPricings[]` | array | 모델별 단가. **단가 미설정 모델도 포함** |

					**gradingPolicy** — 목업 `채점 · 고정 · claude-x` 행

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `gradingPolicyId` | UUID | 정책 식별자 |
					| `policyVersion` | int | 정책 버전. 바꿀 때마다 올라간다 |
					| `modelId` | UUID | 현재 채점 모델 |
					| `modelDisplayName` | string | 표시명(예: `claude-opus-5`) |
					| `modelCode` | string | 모델 코드 |
					| `effectiveFrom` | datetime | 적용 시작 시각 |
					| `changeReason` | string? | 변경 사유 |
					| `activeCalibration` | object? | 현재 유효한 캘리브레이션 버전 |
					| `runningCalibration` | object? | **진행 중인 재캘리브레이션.** 없으면 `null` |

					**calibration 객체** — `calibrationVersionId` · `versionCode` ·
					`status`(`PENDING`·`RUNNING`·`ACTIVE`·`FAILED`·`SUPERSEDED`) · `startedAt` · `completedAt` ·
					`progress`

					**progress** — `totalOrganizations` · `pending` · `running` · `succeeded` · `failed` ·
					`completionRate`(0~1). 확인 모달의 `전 기관 재캘리브레이션` 진행률에 쓴다.

					**tierMappings[]**

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `tierPolicyId` | UUID | 매핑 식별자 |
					| `featureCode` | string | 적용 기능. **v07 기준 `CODE_SESSION` 하나뿐** |
					| `tierCode` | enum | `ACCURACY_FIRST` · `BALANCED` · `COST_FIRST` |
					| `modelId` · `modelDisplayName` · `modelCode` | | 이 티어가 실제로 호출할 모델 |
					| `policyVersion` | int | 매핑 버전 |
					| `effectiveFrom` | datetime | 적용 시작 시각 |

					**modelPricings[]** — 목업 `단가 · 100만 토큰당`

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `modelId` · `modelCode` · `modelDisplayName` · `provider` | | 모델 정보 |
					| `status` | string | `ACTIVE` · `INACTIVE`. INACTIVE 모델은 선택 대상에서 빼야 한다 |
					| `inputPricePerMillionTokens` | decimal? | 100만 토큰당 입력 단가. **미설정이면 `null`** |
					| `outputPricePerMillionTokens` | decimal? | 출력 단가. 미설정이면 `null` |
					| `cachedInputPricePerMillionTokens` | decimal? | 캐시 입력 단가 |
					| `currencyCode` | string? | 통화. `USD` |
					| `pricingMissing` | boolean | **`true` 면 화면에 `단가 미설정`으로 표시**하고 합계에서 뺀다 |
					| `priceEffectiveFrom` · `priceUpdatedAt` | datetime? | 단가 적용·수정 시각 |

					⚠️ `pricingMissing=true` 인 모델을 **0원으로 계산하지 마세요.**
					목업 SA-03: *"0으로 합산하면 청구액이 실제보다 작아 보인다."*

					## 초기 설정 전이면

					`gradingPolicy` 가 `null` 이고 `tierMappings` 가 비어 있을 수 있다.
					`ai_model` · `platform_grading_model_policy` 에 시드 데이터가 들어가야 채워진다.
					화면은 이 경우 빈 상태를 그려야 한다(오류가 아니다).
					"""
	)
	@GetMapping("/model-settings")
	public ResponseEntity<PlatformModelSettingResponse> findModelSettings() {
		return ResponseEntity.ok(platformOperationsService.findModelSettings());
	}

	@Operation(
			summary = "채점 모델 변경 (전 기관 재캘리브레이션 유발) | ✅ 사용 가능",
			description = """
					SA-03 ① `채점 · 고정 · claude-x` 행의 변경 액션.

					## ⚠️ 되돌릴 수 없다

					목업: *"채점 모델을 바꾸면 **전 기관 재캘리브레이션**이 필요하고, 버전이 다른 결과끼리는
					화면에서 비교를 막는다."*

					**반드시 확인 모달을 거쳐 호출하세요.** 서버도 `acknowledgeRecalibration=true` 를
					요구해 한 번 더 막는다.

					## 요청 (JSON 본문)

					| 필드 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `modelId` | **필수** | UUID | 새 채점 모델. `GET /model-settings` 의 `modelPricings[].modelId` 에서 고른다 |
					| `calibrationVersionCode` | **필수** | string | 새로 만들 캘리브레이션 버전 코드 |
					| `acknowledgeRecalibration` | **필수** | boolean | **반드시 `true`.** false·누락이면 400 |
					| `changeReason` | 선택 | string | 변경 사유(감사·이력용) |

					**`calibrationVersionCode` 는 조회해서 고르는 값이 아니라 직접 짓는 이름이다.**

					- 형식: `^[A-Z][A-Z0-9_]*$` — 대문자로 시작, 대문자·숫자·밑줄만. 100자 이하
					- **전체 UNIQUE** — 이미 쓴 값이면 409
					- 예: `CAL_2026_08_V1`

					## 응답

					변경 후의 **모델·단가 설정 전체**(`GET /model-settings` 와 같은 구조).
					화면을 그대로 다시 그리면 된다. `gradingPolicy.runningCalibration` 에 방금 만든
					재캘리브레이션 버전이 담긴다.

					## 한 번 호출하면 벌어지는 일

					한 트랜잭션에서:

					1. 현재 활성 채점 정책을 `SUPERSEDED` 로 닫고 **새 버전 발급**
					2. 새 캘리브레이션 버전 생성(`PENDING`)
					3. **삭제되지 않은 전 기관**에 재캘리브레이션 대기 행 생성

					⚠️ **실제 재캘리브레이션 실행은 별도 배치 담당이고, 그 배치가 아직 없다.**
					따라서 상태가 `PENDING` 에서 넘어가지 않으며, **다시 바꾸려 하면 계속 409 가 난다.**
					테스트 중이라면 개발 DB 에서 해당 버전 상태를 직접 정리해야 한다.

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 400 | `acknowledgeRecalibration` 이 true 가 아님 · 버전 코드 형식 오류 |
					| 400 `AI_MODEL_NOT_AVAILABLE` | 없는 모델이거나 `INACTIVE` 상태 |
					| 409 `CALIBRATION_VERSION_CODE_TAKEN` | 이미 쓴 버전 코드 |
					| 409 `CALIBRATION_IN_PROGRESS` | **재캘리브레이션이 이미 진행 중** — 두 버전이 동시에 돌면 어느 쪽이 비교 기준인지 알 수 없다 |
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
			summary = "티어 ↔ 모델 매핑 변경 | ✅ 사용 가능",
			description = """
					SA-03 ① `코드 세션 · 정확도 우선 / 균형 / 비용 우선` 3티어 매핑을 바꾼다.
					**한 번에 한 티어씩** 바꾼다.

					## 요청 (JSON 본문)

					| 필드 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `featureCode` | **필수** | enum | **`CODE_SESSION` 하나뿐이다**(v07 기준) |
					| `tierCode` | **필수** | enum | `ACCURACY_FIRST` · `BALANCED` · `COST_FIRST` |
					| `modelId` | **필수** | UUID | 그 (기능, 티어)가 실제로 호출할 모델 |
					| `changeReason` | 선택 | string | 변경 사유 |

					## 응답

					변경 후의 **모델·단가 설정 전체**(`GET /model-settings` 와 같은 구조).
					화면을 그대로 다시 그리면 된다.

					## 채점 모델 변경과 다르다

					| | 채점 모델 | 티어 매핑 |
					|---|---|---|
					| 재캘리브레이션 | **발생** | **없음** |
					| 확인 플래그 | 필수 | 불필요 |
					| 되돌리기 | 사실상 불가 | 다시 바꾸면 됨 |

					코드 세션은 점수가 아니라 **산출물**이라 버전 간 비교 문제가 없다.
					화면의 확인 모달은 "정말 바꿀지"만 묻는 가벼운 확인이면 된다.

					(기능, 티어) 조합별로 버전이 올라가고 이전 활성 버전은 `SUPERSEDED` 로 닫힌다.

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 400 | 필수 누락 · `featureCode` 가 `CODE_SESSION` 이 아님 |
					| 400 `AI_MODEL_NOT_AVAILABLE` | 없는 모델이거나 `INACTIVE` 상태 |
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
			summary = "모델 단가 수정 | ✅ 사용 가능",
			description = """
					SA-03 ① `단가 · 모델별 입력·출력 토큰 단가` 행의 `입력`/`수정` 액션.

					## 요청

					**경로 변수** — `modelId` (**필수**, UUID). 조회 응답의 `modelPricings[].modelId`

					**JSON 본문** — 단가는 모두 **100만 토큰당** 값이다.

					| 필드 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `inputPricePerMillionTokens` | 조건부 | decimal | 입력 단가. 0 이상 |
					| `outputPricePerMillionTokens` | 조건부 | decimal | 출력 단가. 0 이상 |
					| `cachedInputPricePerMillionTokens` | 선택 | decimal | 캐시 입력 단가 |
					| `priceUnitTokenCount` | 선택 | int | 단가 기준 토큰 수. 기본 `1000000` |

					**입력·출력 단가는 함께 움직인다.**

					| 보내는 값 | 결과 |
					|---|---|
					| 입력·출력 **둘 다 값** | 단가 설정 |
					| 입력·출력 **둘 다 `null`** | **단가 미설정으로 되돌림** |
					| 한쪽만 값 | **400** — 쌍이 맞지 않음 |

					캐시 단가는 입력 단가가 있을 때만 설정할 수 있다(입력이 `null` 인데 캐시만 보내면 400).

					## ⚠️ 미설정과 0은 다르다

					단가를 지우려면 **`null`** 을 보내세요. **`0` 은 '무료'라는 뜻**이라 전혀 다릅니다.

					| | 사용량 집계에서 |
					|---|---|
					| `null`(미설정) | 비용 합계에서 **제외**하고 `unpricedCallCount` 로 따로 센다 |
					| `0` | 0원으로 **합산**된다 |

					목업 SA-03: *"0으로 합산하면 청구액이 실제보다 작아 보인다."*

					## 응답

					변경 후의 **모델·단가 설정 전체**(`GET /model-settings` 와 같은 구조).

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 400 | 입력·출력 단가 쌍 불일치 · 캐시 단가만 지정 · 음수 |
					| 400 `AI_MODEL_NOT_AVAILABLE` | 없는 모델 |
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
			summary = "슈퍼어드민 계정 목록 | ✅ 사용 가능",
			description = """
					SA-03 ② `슈퍼어드민 계정` 탭의 목록.

					## 요청

					없다. 파라미터·본문 모두 필요 없다.

					## 응답

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `activeCount` | int | **활성**(`ACTIVE`) 슈퍼어드민 수 |
					| `content[]` | array | 계정 목록. 생성순 |

					**content[] 각 항목**

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `memberId` | UUID | 계정 식별자. 정지·재활성에 쓴다 |
					| `name` | string? | 이름. **초대만 되고 가입 전이면 `null`** → 화면에 `—` |
					| `email` | string | 이메일 |
					| `status` | enum | `ACTIVE`(활성) · `PENDING`(초대됨) · `INACTIVE`(정지) |
					| `lastLoginAt` | datetime? | 최근 로그인. 없으면 `null` → `대기 중` |
					| `createdAt` | datetime | 계정 생성 시각 |
					| `deactivatable` | boolean | `false` 면 **정지 버튼을 잠근다** |

					## `deactivatable` 이 핵심이다

					활성 슈퍼어드민이 **1명뿐이면 그 계정은 `false`** 다.
					목업: *"마지막 한 명은 정지할 수 없다."* 정지하면 플랫폼에 들어갈 사람이 아무도 없어지고,
					오퍼레이터와 달리 **풀어 줄 상위 권한이 아예 없어** 복구가 불가능하다.

					화면에서 미리 잠가야 사용자가 409 를 만나지 않는다.

					## 기관 컬럼이 없다

					슈퍼어드민은 어느 기관에도 속하지 않는다(`app_user.org_id IS NULL`).
					`PENDING` 계정도 목록에 포함되므로 초대 현황을 이 목록에서 볼 수 있다.
					"""
	)
	@GetMapping("/super-admins")
	public ResponseEntity<SuperAdminListResponse> findSuperAdmins() {
		return ResponseEntity.ok(platformOperationsService.findSuperAdmins());
	}

	@Operation(
			summary = "슈퍼어드민 초대 | ✅ 사용 가능",
			description = """
					SA-03 ② `+ 계정 초대`. 계정 자리를 만들고 초대 메일을 보낸다.

					## 요청 (JSON 본문)

					| 필드 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `email` | **필수** | string | 초대할 이메일. **이 API 의 유일한 입력이다** |

					**헤더 (선택)** — `X-Request-Id: {문자열}` 추적용. 생략하면 서버가 만든다.

					역할을 고르는 칸이 없다 — 이 화면에서 보내는 초대는 슈퍼어드민 하나뿐이라
					**초대하는 화면이 곧 역할**이다. 기관·기수·반도 없다 — 슈퍼어드민은 어느 기관에도
					속하지 않는다(`app_user.org_id IS NULL`).

					**이메일 도메인 제한이 없다.** 플랫폼 도메인을 저장하는 곳이 없고, 오퍼레이터 초대와
					같은 이유(초대 시점에는 그 도메인 메일함을 아직 가질 수 없다)가 적용된다.

					## 응답 — `201 Created`

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `memberId` | UUID | 생성된 계정 자리 |
					| `email` | string | 초대 메일 수신 주소 |
					| `status` | enum | 항상 `PENDING`(목업 `초대됨`) |
					| `invitedAt` | datetime | 초대 원장·최초 토큰 생성 시각 |
					| `accounts` | object | **초대 반영 후의 슈퍼어드민 목록 전체** |

					`accounts` 는 `GET /super-admins` 와 같은 구조(`content[]` · `activeCount`)다.
					**목록을 따로 재조회할 필요 없이 표를 그대로 다시 그리면 된다.**

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 400 | 이메일 형식 오류 |
					| 403 | 슈퍼어드민이 아닌 호출자 |
					| 409 `ALREADY_INVITED` | 이미 등록되었거나 초대가 진행 중인 이메일 |
					| 502 `INVITE_MAIL_FAILED` | 메일 발송 실패 |

					502 여도 **계정 자리와 초대 기록은 남는다.** 목록에 `PENDING` 으로 계속 보이므로
					같은 주소로 다시 초대하면 그 자리를 되살려 재발송된다.

					> 오퍼레이터 초대와 달리 **취소·재발송 전용 API 가 없다.** 목업 SA-03 ② 표에
					> 그 액션이 없기 때문이다. 필요해지면 오퍼레이터 쪽과 같은 방식으로 추가할 수 있다
					> (member 도메인의 재발송 엔진이 이미 슈퍼어드민 목적을 처리한다).
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
			summary = "슈퍼어드민 정지 · 재활성 | ✅ 사용 가능",
			description = """
					SA-03 ② 표의 행별 액션 `정지` / `재활성`.

					## 요청

					**경로 변수**

					| 변수 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `memberId` | **필수** | UUID | 목록 응답의 `memberId` |

					**JSON 본문**

					| 필드 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `status` | **필수** | enum | `ACTIVE`(재활성) 또는 `INACTIVE`(정지)만. 그 외는 400 |
					| `reason` | 선택 | string | 변경 사유(예: `휴직 처리`). 감사 이력에 남는다 |

					`PENDING` 은 지정할 수 없다 — 초대 흐름이 설정하는 값이다.

					## 응답

					**변경 후의 슈퍼어드민 목록 전체**(`GET /super-admins` 와 같은 구조).
					`deactivatable` 이 함께 갱신되므로 **표를 그대로 다시 그리면 된다.**

					## 마지막 활성 슈퍼어드민은 정지할 수 없다

					409 `LAST_SUPER_ADMIN`. 정지하면 플랫폼에 들어갈 사람이 아무도 없어지고,
					오퍼레이터와 달리 **풀어 줄 상위 권한이 아예 없어 복구가 불가능하다.**

					화면은 `deactivatable=false` 인 행의 정지 버튼을 **미리 잠가** 이 오류를 만나지 않게 한다.

					⚠️ **`PENDING` 계정은 활성 수에 포함되지 않는다.** 목록에 2명이 보여도 하나가 `PENDING`
					이면 `activeCount=1` 이라 정지가 차단된다 — 초대장만으로는 플랫폼에 들어올 수 없기 때문이다.

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 400 | `status` 누락, 또는 `ACTIVE`·`INACTIVE` 외의 값 |
					| 404 `SUPER_ADMIN_NOT_FOUND` | 없는 계정이거나 슈퍼어드민이 아님 |
					| 409 `LAST_SUPER_ADMIN` | 마지막 활성 슈퍼어드민 정지 시도 |
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
		return ResponseEntity.ok(platformOperationsService.updateSuperAdminStatus(
				memberId, request.status(), request.reason(), currentUserResolver.resolveCurrentMemberId()
		));
	}
}
