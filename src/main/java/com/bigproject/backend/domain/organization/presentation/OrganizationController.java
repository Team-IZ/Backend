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
import org.springframework.http.MediaType;
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
@RequestMapping(value = "/organizations", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class OrganizationController {

	private final OrganizationService organizationService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(
			operationId = "findOrganizations",
			summary = "기관 목록 조회 | ✅ 사용 가능",
			description = """
					SA-01 기관 목록 표를 채운다. 이름 검색·상태 필터·정렬을 **모두 서버가 처리**하므로
					화면은 파라미터만 넘기면 된다(클라이언트에서 다시 거르지 않는다).

					## 요청 (쿼리 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `query` | 선택 | string | 기관명 부분검색. 대소문자 구분 없음. 비우면 전체 |
					| `status` | 선택 | enum | `ACTIVE` · `SUSPENDED` · `DELETION_PENDING` · `DELETED`. **비우면 전체**(화면의 `상태 · 전체`) |
					| `sort` | 선택 | enum | `CREATED_AT_DESC`(기본) · `CREATED_AT_ASC` · `NAME_ASC` · `NAME_DESC` |
					| `page` | 선택 | int | 0부터 시작. 기본 `0` |
					| `size` | 선택 | int | 페이지당 개수. 기본 `20`, 최대 `100` |

					⚠️ 기수 수·교육생 수·비용은 **정렬 대상이 아니다.** 별도 배치 집계라 페이지를 자른 뒤에
					채워지므로 전역 정렬이 성립하지 않는다.

					## 응답

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `content[]` | array | 기관 목록. 각 항목 구조는 아래 |
					| `page` | int | 현재 페이지(0부터) |
					| `size` | int | 페이지당 개수 |
					| `totalElements` | long | 조건에 맞는 전체 건수 |
					| `totalPages` | int | 전체 페이지 수 |

					**content[] 각 항목**

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `organizationId` | UUID | 기관 식별자. 상세 이동에 쓴다 |
					| `name` | string | 기관명 |
					| `slug` | string? | URL용 짧은 식별값. 미지정이면 `null` |
					| `displayCode` | string? | 화면 표시용 코드. 미지정이면 `null` |
					| `emailDomain` | string? | 초대 허용 도메인. `null`이면 제한 없음 |
					| `status` | enum | `ACTIVE` · `SUSPENDED` · `DELETION_PENDING` · `DELETED` |
					| `operatorUnassigned` | boolean | `true`면 `오퍼레이터 미배정` 배지. operators가 비었을 때 |
					| `budgetExceeded` | boolean | `true`면 `예산 초과` 배지. 예산이 0이면 항상 false |
					| `operators[]` | array | `{ memberId, name, email }`. 목록의 `박지현 외 1` 렌더링용 |
					| `cohorts` | object | `{ total, running, closed }` — 목업 `기수 3 (진행 2 · 종료 1)` |
					| `traineeCount` | int | 활성 교육생 수(기수를 나간 인원 제외) |
					| `activeSessionCount` | int | 진행 중 세션 수(`IN_PROGRESS`·`PAUSED`) |
					| `currentMonthAiCost` | decimal | 이번 달(UTC) AI 비용 |
					| `monthlyAiBudget` | decimal | 월 예산. 정책이 없으면 `0` |
					| `budgetUsageRate` | decimal? | 소진율(0~1). 예산이 0이면 `null` |
					| `currencyCode` | string? | 통화. 플랫폼 공통 `USD`. 정책이 없으면 `null` |
					| `dataRetentionDays` | int | 보존기간(일). 정책이 없으면 `0` |
					| `defaultDisclosureScope` | enum? | 기본 공개범위. 정책이 없으면 `null` |
					| `createdAt` | datetime | 생성 시각 (ISO-8601 UTC) |
					| `deletedAt` | datetime? | 삭제 시각. 살아 있으면 `null` |
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
			operationId = "findPlatformSummary",
			summary = "플랫폼 전체 집계 조회 | ✅ 사용 가능",
			description = """
					SA-01 상단 지표 카드 4개를 채운다. 전 기관을 합산한 값이다.

					목록 조회(`GET /organizations`)와 **의존 관계가 없으므로 병렬로 호출**하면 된다.

					## 요청

					없다. 파라미터·본문 모두 필요 없다.

					## 응답

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `period` | string | 집계 기준 월 (`yyyy-MM`, UTC) |
					| `organizations.total` | int | 총 기관 수 (**삭제된 기관 제외**) |
					| `organizations.active` | int | 활성 기관 수 |
					| `organizations.suspended` | int | 정지 기관 수 |
					| `traineeCount` | int | 플랫폼 전체 활성 교육생 수 |
					| `activeSessionCount` | int | 진행 중 세션 수(`IN_PROGRESS`·`PAUSED`) |
					| `aiCost.totalCost` | decimal | 이번 달 AI 비용 합계 |
					| `aiCost.totalMonthlyBudget` | decimal | 전 기관 월 예산 합계 |
					| `aiCost.budgetUsageRate` | decimal? | 소진율(0~1). 예산 합계가 0이면 `null` |
					| `aiCost.changeRateVsPrevMonth` | decimal? | 전월 대비 증감률. 전월이 0이면 `null` |
					| `aiCost.currencyCode` | string | 통화. 플랫폼 공통 `USD` |
					| `storage.totalBytes` | long | 총 저장 바이트 |
					| `storage.changeRateVsPrevMonth` | decimal? | 전월 대비 증감률. 전월이 0이면 `null` |
					| `storage.averageBytesPerOrganization` | long | 기관 1곳당 평균 바이트 |

					**증감률이 `null`인 경우** 화면에서 `—`로 표시한다. 0%와 구분해야 한다 —
					전월 값이 없어 계산할 수 없는 것이지 변화가 없다는 뜻이 아니다.
					"""
	)
	@GetMapping("/summary")
	public ResponseEntity<PlatformSummaryResponse> findPlatformSummary() {
		return ResponseEntity.ok(organizationService.findPlatformSummary());
	}

	@Operation(
			operationId = "checkNameAvailability",
			summary = "기관명 중복 확인 | ✅ 사용 가능",
			description = """
					SA-01 생성 모달의 "입력 중 실시간 중복 확인"(✓/✗)에 쓴다.
					타이핑마다 호출하지 말고 **디바운스(300ms 정도)** 를 걸어 주세요.

					## 요청 (쿼리 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `name` | **필수** | string | 확인할 기관명. 빈 문자열이면 400 |

					## 응답

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `name` | string | 확인을 요청한 원본 이름 |
					| `normalizedName` | string | 중복 판정 기준값(트림 + 소문자) |
					| `available` | boolean | `true`면 사용 가능 |
					| `reason` | string? | 사용 불가 사유. `available=true`면 `null` |

					⚠️ **이건 입력 중 힌트일 뿐 보장이 아니다.** 확인과 생성 사이에 다른 요청이 같은 이름을
					선점할 수 있으므로, 최종 방어는 생성 API 의 409 다. 화면은 생성 실패도 처리해야 한다.

					**삭제된 기관의 이름도 사용 불가로 나온다.** `normalized_name` 이 전체 UNIQUE 라
					물리 파기 전까지 이름이 선점 상태로 남는다.
					"""
	)
	@GetMapping("/name-availability")
	public ResponseEntity<OrganizationNameAvailabilityResponse> checkNameAvailability(
			@RequestParam @NotBlank String name
	) {
		return ResponseEntity.ok(organizationService.checkNameAvailability(name));
	}

	@Operation(
			operationId = "createOrganization",
			summary = "기관 생성 및 기본 운영 정책 초기화 | ✅ 사용 가능",
			description = """
					SA-01 `기관 생성 (테넌트 프로비저닝)` 모달. 기관과 **최초 운영 정책(버전 1)을 함께** 만든다.
					정책 기본값은 월 예산 0 · 통화 USD · 공개범위 SUMMARY 다.

					## 요청 (JSON 본문)

					| 필드 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `name` | **필수** | string | 기관명. 전체에서 유일해야 한다 |
					| `dataRetentionDays` | **필수** | int | 보존기간. **`90` · `180` · `365` 중 하나만** |
					| `emailDomain` | 선택 | string | 기관 대표 도메인(예: `codebase.ac.kr`). 생략하거나 `null` |
					| `slug` | 선택 | string | URL용 짧은 식별값. 소문자·숫자·하이픈, 64자 이하, 전체 UNIQUE |
					| `displayCode` | 선택 | string | 화면 표시용 코드. 대문자로 시작, 대문자·숫자·밑줄, 32자 이하 |

					⚠️ **선택 필드에 빈 문자열(`""`)을 보내면 400 이다.** 안 쓸 거면 필드를 아예 생략하거나
					`null` 로 보내세요.

					⚠️ `emailDomain` 은 저장만 되고 **초대를 제한하지 않는다.** 오퍼레이터는 기관의 첫 계정이라
					초대 시점에 그 기관 메일함이 없기 때문이다.

					**헤더 (선택)** — `Idempotency-Key: {UUID}`
					같은 키 + 같은 내용으로 재요청하면 최초 생성 결과를 그대로 돌려준다(기관이 두 개 생기지 않는다).
					같은 키로 **다른 내용**을 보내면 409 `ORG_IDEMPOTENCY_CONFLICT`. 생략하면 멱등 처리를 하지 않으므로
					네트워크 재시도 시 기관이 중복 생성될 수 있다.

					## 응답 — `201 Created`

					목록 조회의 `content[]` 항목과 **같은 구조**다. 주요 필드만 적는다.

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `organizationId` | UUID | **이후 모든 API 호출에 쓰는 식별자** |
					| `status` | enum | 항상 `ACTIVE` |
					| `operatorUnassigned` | boolean | 항상 `true` — 오퍼레이터가 아직 없다 |
					| `dataRetentionDays` | int | 요청값 그대로 |
					| `currencyCode` | string | `USD` |
					| `monthlyAiBudget` | decimal | `0` |
					| 나머지 | | 목록 조회 응답과 동일 |

					**생성 직후 화면 처리** — 목록으로 돌아가면 `GET /organizations` 와
					`GET /organizations/summary` 를 **둘 다** 다시 불러야 카드 숫자가 맞는다.

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 400 | 필수 누락 · 형식 오류 · `dataRetentionDays` 가 90/180/365 밖 |
					| 409 `ORG_NAME_TAKEN` | 이미 사용 중인 기관명(삭제된 기관 이름 포함) |
					| 409 `ORG_IDEMPOTENCY_CONFLICT` | 같은 멱등키로 다른 내용을 보냄 |
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
			operationId = "findOrganization",
			summary = "기관 상세 조회 | ✅ 사용 가능",
			description = """
					SA-02 ① 개요 탭의 지표 카드와 정보 행을 채운다.

					## 요청 (경로 변수)

					| 변수 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `organizationId` | **필수** | UUID | 목록 응답의 `organizationId` |

					본문·쿼리 파라미터는 없다.

					## 응답

					**목록 조회 `content[]` 항목과 완전히 같은 구조다.** 필드 설명은
					`GET /organizations` 문서를 참고하세요.

					## 화면을 채우려면 이것만으로 부족하다

					개요 탭에 필요한 값이 여러 API 에 흩어져 있다.

					| 화면 요소 | 어디서 |
					|---|---|
					| 기관명·상태·오퍼레이터·기수 수·교육생 수·AI 비용 | **이 API** |
					| 저장량 (`9.4 GB · 전월 대비 +8%`) | `GET .../operations/usage` 의 `storage` |
					| 기수 목록 표 | `GET .../cohorts` |

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 404 `ORG_NOT_FOUND` | 없는 기관 |

					**삭제된 기관도 조회된다**(`status=DELETED`, `deletedAt` 채워짐). 복구 화면에서 쓰기 위함이다.
					"""
	)
	@GetMapping("/{organizationId}")
	public ResponseEntity<OrganizationResponse> findOrganization(@PathVariable UUID organizationId) {
		return ResponseEntity.ok(organizationService.findOrganization(organizationId));
	}

	@Operation(
			operationId = "findOrganizationCohorts",
			summary = "기관 기수 목록 조회 (읽기전용) | ✅ 사용 가능",
			description = """
					SA-02 ① 개요 하단의 `기수 · 읽기전용` 표를 채운다.

					## 요청 (경로 변수)

					| 변수 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `organizationId` | **필수** | UUID | 기관 식별자 |

					**페이지네이션이 없다.** 기수는 기관당 많아야 수십 개라 전부 내려준다.

					## 응답

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `organizationId` | UUID | 요청한 기관 |
					| `content[]` | array | 기수 목록. **시작일 내림차순**(최근 기수가 위) |

					**content[] 각 항목**

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `cohortId` | UUID | 기수 식별자 |
					| `name` | string | 기수명 (예: `7기`) |
					| `status` | string | `PLANNED`(예정) · `RUNNING`(진행 중) · `CLOSED`(종료) |
					| `classCount` | int | 이 기수의 반 수(삭제된 반 제외) |
					| `traineeCount` | int | 현재 소속 교육생 수(**나간 인원 제외**) |
					| `startDate` | date? | 시작일 (`yyyy-MM-dd`). 미정이면 `null` |
					| `endDate` | date? | 종료일. 미정이면 `null` |

					## 조회 전용이다

					기수 개설·반 편성·명단 등록은 **기관 안에서 오퍼레이터가** 한다(OP-06).
					목업: *"기수를 여기서 열지 않는다. 지원·과금 맥락의 읽기전용 가시성만 둔다."*
					따라서 이 화면에 생성·수정 버튼을 두지 않는다.
					"""
	)
	@GetMapping("/{organizationId}/cohorts")
	public ResponseEntity<OrganizationCohortListResponse> findOrganizationCohorts(@PathVariable UUID organizationId) {
		return ResponseEntity.ok(organizationService.findOrganizationCohorts(organizationId));
	}

	@Operation(
			operationId = "updateOrganization",
			summary = "기관 이름 또는 운영 상태 변경 | ✅ 사용 가능",
			description = """
					**기관명을 바꿀 수 있는 유일한 API 다.** 운영 설정(`PUT .../operations/settings`)에는
					이름 필드가 없다.

					상태(`status`) 변경은 설정 탭 API 와 기능이 겹친다. 화면 진입점이 설정 탭 한 곳이므로
					**상태만 바꿀 때는 그쪽을 쓰고, 이 API 는 이름 변경 용도로** 사용하세요.

					## 요청

					**경로 변수**

					| 변수 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `organizationId` | **필수** | UUID | 기관 식별자 |

					**JSON 본문**

					| 필드 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `status` | **필수** | enum | `ACTIVE`(활성) 또는 `SUSPENDED`(정지)만 지정 가능 |
					| `name` | 선택 | string | 새 기관명. `null`·빈 값이면 **이름을 바꾸지 않는다** |

					⚠️ **`status` 는 이름만 바꿀 때도 필수다.** 현재 상태를 그대로 실어 보내세요.
					빠뜨리면 400 이다.

					⚠️ Swagger UI 의 `name` 예시값 `"string"` 을 지우지 않고 실행하면 **기관명이 실제로
					`string` 으로 바뀐다.** 이름을 안 바꿀 거면 필드를 비우세요.

					## 응답

					변경된 기관 정보. **목록 조회 `content[]` 항목과 같은 구조**다.

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 400 | `status` 누락, 또는 `ACTIVE`·`SUSPENDED` 외의 값 |
					| 404 `ORG_NOT_FOUND` | 없는 기관 |
					| 409 `ORG_ALREADY_DELETED` | 삭제된 기관. 되살리려면 `POST /{organizationId}/restore` |
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
			operationId = "deleteOrganization",
			summary = "기관 soft-delete | ✅ 사용 가능",
			description = """
					SA-02 ④ 설정 탭의 `기관 삭제` + 확인 모달(case 7).
					**즉시 파기가 아니라 보존기간을 두는 soft-delete 다.**

					## 요청

					**경로 변수**

					| 변수 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `organizationId` | **필수** | UUID | 기관 식별자 |

					**JSON 본문**

					| 필드 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `confirmName` | **필수** | string | **삭제할 기관의 정확한 이름.** 저장된 이름과 다르면 400 |

					기관명 입력을 요구하는 이유 — 소속 오퍼레이터·매니저·교육생 **전원이 못 들어오게 되는**
					액션이라 버튼 한 번으로 끝나면 안 된다. 화면 모달에서 사용자가 직접 타이핑하게 하세요.

					**헤더 (선택)** — `Idempotency-Key: {UUID}` (생성 API 와 동일한 규칙)

					## 응답

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `organizationId` | UUID | 삭제된 기관 |
					| `deletedAt` | datetime | 삭제 처리 시각 |
					| `purgeAvailableAt` | datetime | **실제 파기 가능 시각.** 활성 정책의 보존기간(90/180/365일)만큼 뒤 |

					화면에는 *"보존기간(N일)이 지난 뒤 파기됩니다. 그전까지는 복구할 수 있습니다"* 로 안내한다.

					## 삭제 후

					- 목록·상세에서 `status=DELETED`, `deletedAt` 이 채워진 상태로 **계속 조회된다**
					- **운영 설정 변경이 막힌다** — 상태를 되돌리려면 설정 API 가 아니라 복구 API 를 쓴다
					- 기관명은 **파기 전까지 선점 상태**라 같은 이름으로 새 기관을 만들 수 없다
					- 복구: `POST /organizations/{organizationId}/restore`

					**계약만 끝난 경우라면 삭제하지 마세요.** 설정에서 상태를 `SUSPENDED` 로 두면
					로그인만 막히고 데이터는 그대로 남습니다.

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 400 `ORG_DELETE_CONFIRM_MISMATCH` | `confirmName` 이 기관명과 다름 |
					| 404 `ORG_NOT_FOUND` | 없는 기관 |
					| 409 `ORG_ALREADY_DELETED` | 이미 삭제됨 |
					| 409 `ORG_POLICY_NOT_FOUND` | 활성 정책이 없어 보존기간을 계산할 수 없음 |
					| 409 `ORG_IDEMPOTENCY_CONFLICT` | 같은 멱등키로 다른 내용을 보냄 |
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
			operationId = "restoreOrganization",
			summary = "기관 복구 | ✅ 사용 가능",
			description = """
					soft-delete 된 기관을 되살린다. 목업 case 7: *"보존기간이 지난 뒤 파기됩니다.
					**그전까지는 복구할 수 있습니다.**"*

					**삭제 상태를 되돌리는 유일한 방법이다.** 운영 설정으로 `status` 를 `ACTIVE` 로 바꾸는 것은
					막혀 있다 — 삭제 흔적(`deleted_at` 등)이 남은 채 활성이 되면 DB 제약을 위반한다.

					## 요청

					| 변수 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `organizationId` | **필수** | UUID | 삭제된 기관의 식별자 |

					본문 없음.

					## 응답

					복구된 기관 정보. **목록 조회 `content[]` 항목과 같은 구조**다.

					`status` 는 **항상 `ACTIVE`** 로 돌아온다. 삭제 전 상태를 보관하는 컬럼이 없고,
					복구의 의도는 "다시 쓰겠다" 이기 때문이다. 정지 상태로 두고 싶다면 복구 후
					설정에서 `SUSPENDED` 로 바꾸세요.

					함께 정리되는 값 — `deletedAt` · `retentionUntil` · `suspendedAt` 이 비워지고
					파기 예약(`purgeStatus`)도 취소된다.

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 404 `ORG_NOT_FOUND` | 없는 기관 |
					| 409 `ORG_NOT_DELETED` | 삭제되지 않은 기관 |
					| 409 `ORG_ALREADY_DELETED` | **보존기간이 이미 지나 파기 대상** — 복구 불가 |
					| 409 `ORG_NAME_TAKEN` | 삭제된 사이 같은 이름의 기관이 새로 생김 |
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
			operationId = "purgeOrganization",
			summary = "기관 파기 요청 | ⚠️ 사용 불가",
			description = """
					**요청은 접수되지만 데이터가 실제로 지워지지 않는다.** 보존기간 검증과
					`purge_status = IN_PROGRESS` 전이까지만 구현돼 있고, 응답은 항상 `purged=false`다.

					`organization(org_id)`를 참조하는 FK가 52개이고 전부 `ON DELETE RESTRICT`라
					52개 테이블을 위상 정렬 역순으로 지워야 하는데, 한 트랜잭션에 담을 수 없어
					별도 배치가 필요하다. 그 배치가 아직 없다.

					⚠️ 한 번 호출하면 `purge_status`가 `IN_PROGRESS`로 바뀌고 **복구가 막힌다**
					(`POST /{organizationId}/restore`가 보존기간 경과를 이유로 거절한다).
					되돌리려면 DB를 직접 손봐야 하므로 **버려도 되는 기관에만 호출한다.**

					---

					보존기간이 지난 기관의 데이터 파기를 요청한다. 목업 case 8 — **화면이 없고**
					운영자가 API 로 직접 호출하는 경로다.

					## 요청

					| 변수 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `organizationId` | **필수** | UUID | soft-delete 된 기관의 식별자 |

					본문 없음.

					## 응답

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `organizationId` | UUID | 대상 기관 |
					| `deletedAt` | datetime | soft-delete 시각 |
					| `retentionUntil` | datetime | 보존기간 종료 시각 |
					| `requestedAt` | datetime | 파기 요청 접수 시각 |
					| `purged` | boolean | **항상 `false`** — 실제 파기는 아직 수행되지 않는다 |
					| `message` | string | 운영자에게 보여줄 안내 문구 |

					⚠️ **화면에서 `purged` 를 반드시 확인하세요.** 200 이 왔다고 "파기 완료" 로 그리면 안 된다.
					`false` 이므로 **"파기 접수됨"** 으로 표시해야 한다.

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 404 `ORG_NOT_FOUND` | 없는 기관 |
					| 409 `ORG_NOT_DELETED` | soft-delete 되지 않은 기관. 먼저 `DELETE /{organizationId}` |
					| 409 `RETENTION_NOT_MET` | 보존기간이 남음. 응답 메시지에 파기 가능 시각이 담긴다 |

					즉시 물리 삭제 경로는 두지 않는다 — 실수로 지우면 되돌릴 방법이 없다.
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
