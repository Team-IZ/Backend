package com.bigproject.backend.domain.organization.presentation;

import com.bigproject.backend.domain.organization.application.OrganizationService;
import com.bigproject.backend.domain.organization.application.OrganizationSort;
import com.bigproject.backend.domain.organization.domain.OrganizationStatus;
import com.bigproject.backend.domain.organization.presentation.dto.CreateOrganizationRequest;
import com.bigproject.backend.domain.organization.presentation.dto.DeleteOrganizationRequest;
import com.bigproject.backend.domain.organization.presentation.dto.DeleteOrganizationResponse;
import com.bigproject.backend.domain.organization.presentation.dto.PurgeOrganizationResponse;
import com.bigproject.backend.domain.organization.presentation.dto.OrganizationCohortListResponse;
import com.bigproject.backend.domain.organization.presentation.dto.OrganizationListResponse;
import com.bigproject.backend.domain.organization.presentation.dto.OrganizationNameAvailabilityResponse;
import com.bigproject.backend.domain.organization.presentation.dto.OrganizationResponse;
import com.bigproject.backend.domain.organization.presentation.dto.PlatformSummaryResponse;
import com.bigproject.backend.domain.organization.presentation.dto.UpdateOrganizationRequest;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Organization", description = "기관 프로비저닝, 기관 상태, 계약, 기관별 운영 정책, 기관 삭제·복구 API (v2 IA: SA-01 기관 목록 / SA-02 기관 상세)")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Validated
@RestController
@RequestMapping("/organizations")
@RequiredArgsConstructor
public class OrganizationController {

	private final OrganizationService organizationService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(
			summary = "기관 목록 조회",
			description = """
					이름 검색과 상태 필터로 기관 목록을 페이지네이션 조회한다. 목업 SA-01 기관 목록 표에 대응한다.

					**요청**
					- query (선택): 기관명 부분검색
					- status (선택): 운영 상태 필터 (ACTIVE/SUSPENDED/DELETION_PENDING/DELETED)
					- sort (선택, 기본 CREATED_AT_DESC): 정렬 기준. 기수·교육생·비용은 별도 배치 집계라 정렬 대상이 아니다.
					- page (기본 0): 0부터 시작하는 페이지 번호
					- size (기본 20, 최대 100): 페이지당 개수

					**응답**
					- operators: 기관 소속 오퍼레이터 계정 목록 — 목록의 `오퍼레이터` 열(`박지현 외 1`)을 렌더링한다. \
					비어 있으면 operatorUnassigned=true(`오퍼레이터 미배정` 배지)
					- cohorts: 기수 수를 전체/진행(RUNNING)/종료(CLOSED)로 분해한 값
					- traineeCount: 활성 교육생 수(기수를 나간 인원 제외)
					- currentMonthAiCost / monthlyAiBudget / budgetUsageRate: 이번 달(UTC) AI 비용과 예산 대비 소진율. \
					비용이 예산을 넘으면 budgetExceeded=true(`예산 초과` 배지)
					- page/size/totalElements/totalPages 페이지 메타데이터

					**아직 채워지지 않는 값** — slug, displayCode, emailDomain(컬럼 없음 → null), \
					activeSessionCount(세션 테이블 없음 → 0)
					"""
	)
	@GetMapping
	public ResponseEntity<OrganizationListResponse> findOrganizations(
			@RequestParam(required = false) String query,
			@RequestParam(required = false) OrganizationStatus status,
			@RequestParam(required = false) OrganizationSort sort,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
	) {
		return ResponseEntity.ok(organizationService.findOrganizations(query, status, sort, page, size));
	}

	@Operation(
			summary = "플랫폼 전체 집계 조회",
			description = """
					전 기관을 합산한 지표를 조회한다. 목업 SA-01 상단 지표 카드 4개에 대응한다.

					**응답**
					- organizations: 총 기관 수와 활성/정지 수 (삭제된 기관 제외)
					- traineeCount: 플랫폼 전체 활성 교육생 수
					- aiCost: 이번 달 총 비용, 전 기관 예산 합계, 소진율, 전월 대비 증감률
					- storage: 총 저장 바이트, 전월 대비 증감률, 기관 평균

					증감률은 전월 값이 0이면 계산할 수 없어 null을 반환한다(화면에서는 `—`).

					**아직 채워지지 않는 값** — activeSessionCount(세션 테이블 없음 → 0)
					"""
	)
	@GetMapping("/summary")
	public ResponseEntity<PlatformSummaryResponse> findPlatformSummary() {
		return ResponseEntity.ok(organizationService.findPlatformSummary());
	}

	@Operation(
			summary = "기관명 중복 확인",
			description = """
					기관명이 사용 가능한지 확인한다. 목업 SA-01 생성 모달의 "입력 중 실시간 중복 확인"(✓/✗)용이다.

					**요청**
					- name (필수): 확인할 기관명

					**응답**
					- available: 사용 가능 여부. false면 reason에 사유가 담긴다.
					- normalizedName: 중복 판정 기준값(트림 + 소문자)

					이 엔드포인트는 입력 중 힌트일 뿐이고, 최종 방어는 생성 API의 409다 \
					(확인 시점과 생성 시점 사이에 다른 요청이 같은 이름을 선점할 수 있다).
					"""
	)
	@GetMapping("/name-availability")
	public ResponseEntity<OrganizationNameAvailabilityResponse> checkNameAvailability(
			@RequestParam @NotBlank String name
	) {
		return ResponseEntity.ok(organizationService.checkNameAvailability(name));
	}

	@Operation(
			summary = "기관 생성 및 기본 운영 정책 초기화",
			description = """
					새 기관(테넌트)을 생성하고, 동시에 기본값(월 예산 0, 통화 USD, 공개범위 SUMMARY)으로 \
					최초 운영 정책(버전 1)을 함께 발급한다. 목업 SA-01 `기관 생성 (테넌트 프로비저닝)` 모달.

					**요청**
					- name (필수): 기관명
					- emailDomain (선택): 기관 대표 이메일 도메인. 예: `codebase.ac.kr` \
					(형식 검증만 하며, `""` 빈 문자열은 400이므로 쓰지 않으려면 필드를 생략하거나 null로 보낸다). \
					⚠ 이 값은 **오퍼레이터 초대를 제한하지 않는다** — 오퍼레이터는 기관의 첫 계정이라 \
					초대 시점에 기관 메일함이 없기 때문이다. 매니저·교육생 초대(OP-06)에서 쓰일 값이다.
					- dataRetentionDays (필수, 30~3650일): 데이터 보존기간

					**응답**
					- 생성된 기관 정보(organizationId 포함) — 이후 다른 API 호출 시 이 organizationId를 사용한다.
					- 오퍼레이터가 아직 없으므로 operatorUnassigned=true로 응답한다 \
					(목업: "확정하면 활성 · 오퍼레이터 미배정 상태로 목록에 들어온다").
					- 이미 사용 중인 기관명이면 409를 반환한다.

					**멱등 처리** — `Idempotency-Key` 헤더(UUID)를 보내면 같은 키 + 같은 내용의 재요청에 \
					최초 생성 결과를 그대로 반환한다(기관을 두 번 만들지 않는다). 같은 키로 **다른 내용**을 보내면 \
					`ORG_IDEMPOTENCY_CONFLICT`(409)로 거절한다. 헤더를 생략하면 멱등 처리를 하지 않는다.

					slug·displayCode·emailDomain은 v06에서 컬럼이 생겨 실제로 저장된다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "생성 성공"),
			@ApiResponse(responseCode = "409", description = "ORG_NAME_TAKEN · ORG_IDEMPOTENCY_CONFLICT")
	})
	@PostMapping
	public ResponseEntity<OrganizationResponse> createOrganization(
			@Valid @RequestBody CreateOrganizationRequest request,
			@Parameter(description = "재시도 안전을 위한 멱등성 키(UUID). 생략 가능.")
			@RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey
	) {
		OrganizationResponse response = organizationService.createOrganization(
				request, currentUserResolver.resolveCurrentMemberId(), idempotencyKey
		);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@Operation(
			summary = "기관 상세 조회",
			description = """
					organizationId로 기관 상세 정보를 조회한다. 목업 SA-02 ① 개요의 지표 카드와 정보 행을 채운다.

					**요청**
					- organizationId (경로)

					**응답**
					- 목록 조회와 동일한 형태의 단건 정보
					- 존재하지 않는 organizationId면 404를 반환한다.

					개요 카드의 저장량(`9.4 GB · 전월 대비 +8%`)은 이 응답에 없다 — \
					`GET /organizations/{organizationId}/operations/usage`의 storage에서 가져온다.
					"""
	)
	@GetMapping("/{organizationId}")
	public ResponseEntity<OrganizationResponse> findOrganization(@PathVariable UUID organizationId) {
		return ResponseEntity.ok(organizationService.findOrganization(organizationId));
	}

	@Operation(
			summary = "기관 기수 목록 조회 (읽기전용)",
			description = """
					기관에 개설된 기수를 조회한다. 목업 SA-02 ① 개요 하단의 `기수 · 읽기전용` 표에 대응한다.

					**요청**
					- organizationId (경로)

					**응답**
					- 기수별 이름·상태(PLANNED/RUNNING/CLOSED)·반 수·교육생 수·기간(startDate~endDate)
					- 시작일 내림차순 정렬

					조회 전용이다. 기수 개설·반 편성·명단 등록은 기관 안에서 오퍼레이터가 수행한다(OP-06) — \
					목업 주석: "기수를 여기서 열지 않는다. 지원·과금 맥락의 읽기전용 가시성만 둔다."
					"""
	)
	@GetMapping("/{organizationId}/cohorts")
	public ResponseEntity<OrganizationCohortListResponse> findOrganizationCohorts(@PathVariable UUID organizationId) {
		return ResponseEntity.ok(organizationService.findOrganizationCohorts(organizationId));
	}

	@Operation(
			summary = "기관 이름 또는 운영 상태 변경",
			description = """
					기관의 이름과 운영 상태를 변경한다.

					**요청**
					- organizationId (경로)
					- name (선택): null/빈 값이면 이름을 변경하지 않는다. Swagger 예시값 "string"을 그대로 \
					두면 실제로 이름이 "string"으로 바뀌니 주의.
					- status (필수): ACTIVE 또는 SUSPENDED만 직접 지정 가능

					**응답**
					- 변경된 기관 정보
					- 이미 삭제(DELETED)된 기관이면 409, status에 그 외 값을 넣으면 400을 반환한다.

					목업 SA-02 ④ 설정 탭의 `기관 상태` 행은 `PUT /organizations/{organizationId}/operations/settings`로도 \
					바꿀 수 있다. 화면상 진입점은 설정 탭 한 곳이므로 그쪽을 우선 사용한다.
					"""
	)
	@PatchMapping("/{organizationId}")
	public ResponseEntity<OrganizationResponse> updateOrganization(
			@PathVariable UUID organizationId,
			@Valid @RequestBody UpdateOrganizationRequest request
	) {
		OrganizationResponse response = organizationService.updateOrganization(
				organizationId, request, currentUserResolver.resolveCurrentMemberId()
		);
		return ResponseEntity.ok(response);
	}

	@Operation(
			summary = "기관 soft-delete",
			description = """
					기관을 즉시 물리 삭제하지 않고 soft-delete 처리한다. 목업 SA-02 ④ 설정 탭의 `기관 삭제` \
					+ 확인 모달(case 7).

					**요청**
					- organizationId (경로)
					- confirmName (필수): 삭제할 기관의 정확한 이름. 저장된 이름과 다르면 \
					`ORG_DELETE_CONFIRM_MISMATCH`로 거절한다.

					기관명 입력을 요구하는 이유 — 소속 오퍼레이터·매니저·교육생 전원이 못 들어오게 되는 액션이라 \
					버튼 한 번으로 끝나면 안 된다.

					**응답**
					- deletedAt: 삭제 처리 시각
					- purgeAvailableAt: 활성 정책의 보존기간만큼 뒤, 실제 파기 가능 시각

					**즉시 파기가 아니다.** 보존기간이 지나야 파기되고, 그전까지는 \
					`POST /organizations/{organizationId}/restore`로 복구할 수 있다. \
					계약만 끝났다면 삭제가 아니라 운영 설정에서 기관 상태를 SUSPENDED로 두면 된다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "soft-delete 성공"),
			@ApiResponse(responseCode = "400", description = "ORG_DELETE_CONFIRM_MISMATCH · 기관명이 일치하지 않음"),
			@ApiResponse(responseCode = "404", description = "ORG_NOT_FOUND"),
			@ApiResponse(responseCode = "409", description = "ORG_ALREADY_DELETED · ORG_POLICY_NOT_FOUND · ORG_IDEMPOTENCY_CONFLICT")
	})
	@DeleteMapping("/{organizationId}")
	public ResponseEntity<DeleteOrganizationResponse> deleteOrganization(
			@PathVariable UUID organizationId,
			@Valid @RequestBody DeleteOrganizationRequest request,
			@Parameter(description = "재시도 안전을 위한 멱등성 키(UUID). 생략 가능.")
			@RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey
	) {
		return ResponseEntity.ok(organizationService.deleteOrganization(
				organizationId, request, currentUserResolver.resolveCurrentMemberId(), idempotencyKey
		));
	}

	@Operation(
			summary = "기관 복구",
			description = """
					soft-delete된 기관을 되살린다. 목업 case 7: "보존기간이 지난 뒤 파기됩니다. \
					**그전까지는 복구할 수 있습니다.**"

					**요청**
					- organizationId (경로)

					**응답**
					- 복구된 기관 정보. 상태는 ACTIVE로 돌아간다(삭제 전 상태를 보관하는 컬럼이 없고, \
					복구의 의도는 "다시 쓰겠다"이기 때문).

					**오류**
					- `ORG_NOT_DELETED`: 삭제되지 않은 기관
					- `ORG_ALREADY_DELETED`: 보존기간이 이미 지나 파기 대상
					- `ORG_NAME_TAKEN`: 삭제된 사이 같은 이름의 기관이 새로 생긴 경우
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "복구 성공"),
			@ApiResponse(responseCode = "404", description = "ORG_NOT_FOUND"),
			@ApiResponse(responseCode = "409", description = "ORG_NOT_DELETED · ORG_ALREADY_DELETED · ORG_NAME_TAKEN")
	})
	@PostMapping("/{organizationId}/restore")
	public ResponseEntity<OrganizationResponse> restoreOrganization(@PathVariable UUID organizationId) {
		return ResponseEntity.ok(organizationService.restoreOrganization(
				organizationId, currentUserResolver.resolveCurrentMemberId()
		));
	}

	@Operation(
			summary = "기관 파기 요청",
			description = """
					보존기간이 지난 기관의 데이터 파기를 요청한다. 목업 case 8 — 화면이 없고 \
					운영자가 API로 직접 호출하는 경로다.

					**요청**
					- organizationId (경로)

					**오류**
					- `RETENTION_NOT_MET`: 보존기간이 남아 파기할 수 없다. 즉시 물리 삭제 경로는 두지 않는다 — \
					실수로 지우면 되돌릴 방법이 없다.
					- `ORG_NOT_DELETED`: soft-delete되지 않은 기관

					⚠ 현재는 **보존기간 검증까지만** 구현돼 있다. 실제 데이터 파기는 cohort·app_user·ai_usage 등 \
					테넌트 데이터 전체를 지우는 작업이라 별도 배치가 담당해야 하며, 응답의 purged는 항상 false다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "파기 요청 접수(보존기간 충족)"),
			@ApiResponse(responseCode = "404", description = "ORG_NOT_FOUND"),
			@ApiResponse(responseCode = "409", description = "RETENTION_NOT_MET · ORG_NOT_DELETED")
	})
	@PostMapping("/{organizationId}/purge")
	public ResponseEntity<PurgeOrganizationResponse> purgeOrganization(@PathVariable UUID organizationId) {
		return ResponseEntity.ok(organizationService.purgeOrganization(organizationId));
	}
}
