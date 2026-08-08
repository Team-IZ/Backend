package com.bigproject.backend.domain.usagemetering.presentation;

import com.bigproject.backend.domain.usagemetering.application.OperationsService;
import com.bigproject.backend.domain.usagemetering.presentation.dto.CohortCostResponse;
import com.bigproject.backend.domain.usagemetering.presentation.dto.OperationSettingResponse;
import com.bigproject.backend.domain.usagemetering.presentation.dto.OrganizationUsageResponse;
import com.bigproject.backend.domain.usagemetering.presentation.dto.UpdateOperationSettingRequest;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

@Tag(name = "Usage Metering", description = "AI 호출량·토큰·비용, 저장소 사용량, 기관 한도, 비용 집계 API (v2 IA: SA-02 ③④ / OP-06 ⑤)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping(value = "/organizations/{organizationId}/operations", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class OperationsController {

	private final OperationsService operationsService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(
			operationId = "findUsage",
			summary = "기관 월별 저장량·활동·AI 비용 조회 | ✅ 사용 가능",
			description = """
					SA-02 ③ `사용량 · AI 비용` 탭(슈퍼어드민)과 OP-06 ⑤ `비용` 탭(오퍼레이터)이 함께 쓴다.

					**권한** — 슈퍼어드민은 모든 기관, 오퍼레이터는 **자기 기관만**(다른 기관이면 403).
					매니저·교육생은 접근 불가 — 목업: *"매니저에게는 비용을 보여주지 않는다.
					보이면 '비싸니까 세션 짧게'라는 잘못된 압력이 생긴다."*

					## 요청

					| 파라미터 | 위치 | 필수 | 타입 | 설명 |
					|---|---|---|---|---|
					| `organizationId` | 경로 | **필수** | UUID | 기관 식별자 |
					| `period` | 쿼리 | 선택 | `yyyy-MM` | 조회 월(예: `2026-07`). 생략하면 **이번 달(UTC)** |
					| `cohortId` | 쿼리 | 선택 | UUID | 반별 내역(`classCosts`)의 범위. **생략하면 `classCosts` 가 빈 배열** |

					`cohortId` 는 OP-06 비용 탭의 상단 기수 스위처 값을 넘기면 된다.
					SA-02(슈퍼어드민)는 기관 전체를 보므로 생략한다.

					## 응답 — 최상위

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `organizationId` | UUID | 요청한 기관 |
					| `period` | string | 집계 월(`yyyy-MM`) |
					| `currencyCode` | string? | 통화. 플랫폼 공통 `USD` |
					| `aggregationSource` | enum | `SNAPSHOT`(배치 집계) · `LIVE`(즉시 합산). 배치가 붙기 전에는 항상 `LIVE` |
					| `storage` | object | 저장량 |
					| `activity` | object | 사용 규모 |
					| `aiCost` | object | AI 비용 |
					| `cohortCosts[]` | array | 기수별 비용 |
					| `classCosts[]` | array | 반별 비용. **`cohortId` 를 안 보내면 빈 배열** |

					**storage** — 목업 `저장량 구성 총 9.4 GB`

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `totalBytes` | long | 총 저장 바이트 |
					| `codeSubmissionBytes` | long | 코드 제출물(레포·ZIP) |
					| `sessionLogBytes` | long | 문답 원문 |
					| `gradingEvidenceBytes` | long | 채점 근거 |
					| `reportBytes` | long | 리포트·내보내기 PDF |
					| `changeRateVsPrevMonth` | decimal? | 전월 대비 증감률. 전월이 0이면 `null` |

					**activity** — 목업 `활성 교육생 148 / 완료 세션 612 / 채점 회차 1,840 / 발행 리포트 96`

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `activeTrainees` | int | 활성 교육생 수 |
					| `completedSessions` | long | 완료 세션 수(`ended_at` 기준) |
					| `gradingRounds` | long | 채점 회차 수(제출 마감 `submission_due_at` 기준) |
					| `generatedReports` | long | 발행 리포트 수(`published_at` 기준) |

					**aiCost** — 목업 `AI 비용 · 이번 달 $412 / 예산 $600 · 전월 +12%`

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `totalCost` | decimal | 비용 합계. **단가 미설정 호출은 제외** |
					| `monthlyBudget` | decimal | 월 예산 |
					| `budgetUsageRate` | decimal? | 소진율(0~1). 예산이 0이면 `null` |
					| `budgetExceeded` | boolean | 예산 초과 여부. 초과해도 서비스는 중단되지 않는다 |
					| `changeRateVsPrevMonth` | decimal? | 전월 대비 증감률 |
					| `costComplete` | boolean | **`false` 면 합계가 실제보다 작다**(단가 미설정 호출 존재) |
					| `unpricedCallCount` | long | 단가가 없어 합계에서 빠진 호출 수 |
					| `total` | object | `{ calls, inputTokens, outputTokens, cost }` 합계 행 |
					| `models[]` | array | (용도, 모델) 조합별 내역 |

					**models[] 각 항목** — `usageType` · `tier` · `model` · `calls` · `inputTokens` ·
					`outputTokens` · `inputPricePerMillionTokens` · `outputPricePerMillionTokens` ·
					`pricingMissing`(단가 미설정) · `cost`

					**cohortCosts[]** — `cohortId` · `name` · `traineeCount` · `cost` ·
					`costPerTrainee`(인원 0이면 `null`) · `unpricedCallCount`

					**classCosts[]** — `classId` · `name` · `managerName`(담당 없으면 `null`) ·
					`traineeCount` · `sessionCount` · `cost` · `unpricedCallCount`

					## 화면에서 주의할 것

					**① `costComplete=false` 면 금액에 주석을 달아야 한다.**
					단가 미설정 호출을 0으로 더하지 않고 **빼기** 때문에 합계가 실제보다 작다.
					`unpricedCallCount` 건이 빠졌다고 표시하세요 — 목업 SA-03 `단가 미설정` 원칙.

					**② 증감률 `null` 은 0%가 아니다.** 전월 값이 없어 계산 불가라는 뜻이라 `—` 로 그린다.

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 403 | 오퍼레이터가 다른 기관을 조회 |
					| 404 `ORG_NOT_FOUND` | 없는 기관 |
					| 503 `USAGE_UNAVAILABLE` | **기간 집계가 실패한 상태**(목업 case 6) |

					503 일 때 화면은 **사용량 패널만** 오류 상태로 바꾸고 기관 정보·다른 탭은 그대로 보여준다.
					실패분을 빼고 남은 것만 더해 0 처럼 보여주지 않는다 — *"안 쓴 것"과 "못 읽은 것"은 다르다.*
					"""
	)
	@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'OPERATOR')")
	@GetMapping("/usage")
	public ResponseEntity<OrganizationUsageResponse> findUsage(
			@PathVariable UUID organizationId,
			@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth period,
			@RequestParam(required = false) UUID cohortId
	) {
		YearMonth resolvedPeriod = period == null ? YearMonth.now(ZoneOffset.UTC) : period;
		return ResponseEntity.ok(operationsService.findUsage(organizationId, resolvedPeriod, cohortId));
	}

	@Operation(
			operationId = "findOrganizationOperationSettings",
			summary = "기관 운영 설정 조회 | ✅ 사용 가능",
			description = """
					SA-02 ④ 설정 탭을 채운다. 현재 **활성(ACTIVE) 정책 버전**의 값이다.

					## 요청

					| 변수 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `organizationId` | **필수** | UUID | 기관 식별자 |

					본문 없음.

					## 응답

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `organizationId` | UUID | 기관 식별자 |
					| `organizationStatus` | enum | `ACTIVE` · `SUSPENDED` · `DELETION_PENDING` · `DELETED` |
					| `monthlyAiBudget` | decimal | 월 AI 예산 상한. `0` 은 무제한이 아니라 **예산 0** |
					| `currencyCode` | string | 통화. 플랫폼 공통 `USD` 고정, 변경 불가 |
					| `monthlyTokenLimit` | long? | 월 토큰 상한. **`null` 이면 무제한** |
					| `storageLimitBytes` | long? | 저장량 상한(바이트). **`null` 이면 무제한** |
					| `dataRetentionDays` | int | 보존기간. `90` · `180` · `365` 중 하나 |
					| `defaultDisclosureScope` | enum | 신규 기수 공개범위 기본값. `SUMMARY` · `PRIVATE` · `FULL` |
					| `codeSessionTierCode` | enum | 코드 세션 모델 티어. `ACCURACY_FIRST` · `BALANCED` · `COST_FIRST` |
					| `allowManagerInvite` | boolean | 신규 매니저 초대·재발송 허용 |
					| `allowDataExport` | boolean | 신규 데이터 export 생성 허용 |
					| `allowZipSubmission` | boolean | ZIP 코드 제출 허용. 끄면 GitHub 연동만 남는다 |
					| `allowGithubIntegration` | boolean | GitHub 조직 연동 허용(**정책**이며 실제 연결은 별도 흐름) |
					| `enableBigProjectContributionAnalysis` | boolean | 빅프로젝트 기여도 분석 허용 |
					| `policyVersion` | int | 현재 정책 버전. 변경할 때마다 올라간다 |

					⚠️ **변경 API 가 전체 치환이므로 이 응답을 그대로 폼 초기값으로 쓰세요.**
					사용자가 바꾼 필드만 덮어쓰고 나머지는 여기서 받은 값을 그대로 다시 보내야 합니다.

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 404 `ORG_NOT_FOUND` | 없는 기관 |
					| 409 `ORG_POLICY_NOT_FOUND` | 활성 정책이 없음(API 를 거치지 않고 만들어진 데이터) |
					"""
	)
	@PreAuthorize("hasRole('SUPER_ADMIN')")
	@GetMapping("/settings")
	public ResponseEntity<OperationSettingResponse> findSettings(@PathVariable UUID organizationId) {
		return ResponseEntity.ok(operationsService.findSettings(organizationId));
	}

	@Operation(
			operationId = "updateOrganizationOperationSettings",
			summary = "기관 운영 설정 변경 | ✅ 사용 가능",
			description = """
					SA-02 ④ 설정 탭의 저장 액션. **슈퍼어드민 전용**이다(오퍼레이터는 사용량 조회만 가능).

					## ⚠️ 부분 수정(PATCH)이 아니라 전체 치환(PUT)이다

					**보내지 않은 필드는 유지되는 게 아니라 검증 오류(400)가 난다.**
					`GET .../settings` 응답을 폼 초기값으로 받아 두고, 사용자가 바꾼 것만 덮어써서
					**전체를 다시 보내세요.**

					`organization_policy` 는 append-only 이력 테이블이라 기존 행을 고치지 않는다.
					활성 버전을 `SUPERSEDED` 로 닫고 **새 버전을 발급**하므로 `policyVersion` 이 1 올라간다.

					## 요청

					**경로 변수** — `organizationId` (**필수**, UUID)

					**JSON 본문**

					| 필드 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `organizationStatus` | **필수** | enum | `ACTIVE` 또는 `SUSPENDED` 만. 그 외 값은 400 |
					| `monthlyAiBudget` | **필수** | decimal | 0 이상. `0` 은 무제한이 아니라 **예산 0** |
					| `dataRetentionDays` | **필수** | int | **`90` · `180` · `365` 중 하나만** |
					| `defaultDisclosureScope` | **필수** | enum | `SUMMARY` · `PRIVATE` · `FULL` |
					| `codeSessionTierCode` | **필수** | enum | `ACCURACY_FIRST` · `BALANCED` · `COST_FIRST` |
					| `allowManagerInvite` | **필수** | boolean | 신규 매니저 초대·재발송 허용 |
					| `allowDataExport` | **필수** | boolean | 신규 데이터 export 생성 허용 |
					| `allowZipSubmission` | **필수** | boolean | ZIP 코드 제출 허용 |
					| `allowGithubIntegration` | **필수** | boolean | GitHub 조직 연동 허용(정책이며 실제 연결은 별도 흐름) |
					| `enableBigProjectContributionAnalysis` | **필수** | boolean | 빅프로젝트 기여도 분석 허용 |
					| `monthlyTokenLimit` | 선택 | long | 월 토큰 상한. **`null` 이면 무제한**. 보낼 경우 0 초과 |
					| `storageLimitBytes` | 선택 | long | 저장량 상한(바이트). **`null` 이면 무제한**. 0 이상 |

					통화(`currencyCode`)는 **요청 항목이 아니다.** 플랫폼 공통 USD 고정이며 DB CHECK 로도 강제된다.

					## 응답

					새 버전 기준의 운영 설정. **`GET .../settings` 와 완전히 같은 구조**다.
					`policyVersion` 이 올라간 것을 확인하면 저장이 반영된 것이다.

					## 이 API 로 기관 상태도 바뀐다

					`organizationStatus` 가 함께 저장되므로 설정 탭의 `기관 상태` 행이 여기서 처리된다.
					다만 **삭제(`DELETED`)된 기관은 이 API 로 되살릴 수 없다** —
					`POST /organizations/{organizationId}/restore` 를 쓰세요.

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 400 | 필수 누락 · `dataRetentionDays` 가 90/180/365 밖 · `organizationStatus` 가 ACTIVE/SUSPENDED 밖 |
					| 404 `ORG_NOT_FOUND` | 없는 기관 |
					| 409 `ORG_ALREADY_DELETED` | 삭제된 기관 |
					| 409 `ORG_POLICY_NOT_FOUND` | 활성 정책이 없음 |
					"""
	)
	@PreAuthorize("hasRole('SUPER_ADMIN')")
	@PutMapping("/settings")
	public ResponseEntity<OperationSettingResponse> updateSettings(
			@PathVariable UUID organizationId,
			@Valid @RequestBody UpdateOperationSettingRequest request
	) {
		OperationSettingResponse response = operationsService.updateSettings(
				organizationId, request, currentUserResolver.resolveCurrentMemberId()
		);
		return ResponseEntity.ok(response);
	}

	@Operation(
			operationId = "findCohortCost",
			summary = "기수 비용 조회 (OP-06 ⑤) | ✅ 사용 가능",
			description = """
					OP-06 `운영 관리 › 비용` 탭 전체를 이 응답 하나로 그린다.
					프론트 `getCost()` 반환 타입과 **필드명까지 1:1**이라 매핑 코드가 필요 없다.

					## `/usage`와 나눈 이유 — 축이 다르다

					| | `/usage` (SA-02 ③) | `/cost` (OP-06 ⑤) |
					|---|---|---|
					| 묻는 것 | 이번 달에 **무엇을** 얼마나 썼나 | 기수 동안 **어느 달 어느 반이** 튀었나 |
					| 기간 | 한 달 | 기수 시작월 ~ 이번 달 |
					| 형태 | 모델별·기수별·반별 한 달 합계 | **월 × 반 매트릭스** |

					`/usage`에 월 배열을 얹으면 SA-02가 안 쓰는 데이터를 매번 받게 되고,
					월마다 `/usage`를 반복 호출하면 7개월 × 10반을 7번 왕복으로 모으게 된다.

					## 요청

					| 파라미터 | 위치 | 필수 | 타입 | 설명 |
					|---|---|---|---|---|
					| `organizationId` | 경로 | **필수** | UUID | 기관 식별자 |
					| `cohortId` | 쿼리 | **필수** | UUID | 이 탭의 범위는 기수다 |
					| `sort` | 쿼리 | | enum | `NAME`(기본) · `COHORT_AMOUNT` |

					**정렬이 둘뿐인 이유** — 월이 열로 펼쳐졌으므로 *어느 달에 누가 많이 썼나*는
					눈으로 훑는 일이다. 달마다 정렬을 만들면 일곱 개가 되고, 그건 매트릭스가 이미 하는 일이다.

					## 월 범위

					`기수 시작월 ~ min(이번 달, 기수 종료월)`. **아직 오지 않은 달은 담지 않는다** —
					기수가 9월까지여도 7월이면 다섯 칸이다.

					## 방향이 반대인 두 배열 ⚠️

					| 배열 | 순서 | 이유 |
					|---|---|---|
					| `summary.monthly` | **최근이 앞** | 월별 표는 최신이 위 |
					| `classes[].monthly` | **오래된 것이 앞** | 매트릭스는 왼쪽에서 오른쪽으로 시간이 흐른다 |

					## 범위가 섞여 있다

					`summary.total`·`previousTotal`은 **기관 전체**, 나머지는 **선택 기수**다.
					화면도 제목에 각각의 범위를 쓴다.

					## 계산 규칙

					| 값 | 규칙 |
					|---|---|
					| 금액 | **단가가 설정된 호출만** 합산(`pricing_status <> 'UNPRICED'`). 0으로 더하면 청구액이 작아 보인다 |
					| `budget` | `월 예산 × 기수 개월 수`로 **파생**. 기수 단위 예산 컬럼이 스키마에 없다. 정책이 없거나 예산 0이면 `null` |
					| `changePct` | 전월 대비 **퍼센트**(`+12.0`). ⚠️ 다른 API의 `changeRate`(0~1)와 단위가 다르다 |
					| `previousTotal` | 지난달 행이 아예 없으면 `null` — **0과 구분**해야 화면이 `—`를 그린다 |
					| 월 버킷 | **UTC 고정**. 서버 로컬 존을 쓰면 배포 환경에 따라 월 경계가 흔들린다 |
					| `cohorts[]` | 기준 월에 **실제로 비용이 난** 기수만. 평시 1건, 전환기 2건 |

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 404 `ORG_NOT_FOUND` | 없는 기관 **또는 없는 기수** |
					| 403 `ORG_ACCESS_DENIED` | 오퍼레이터가 다른 기관을 조회 |
					"""
	)
	@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'OPERATOR')")
	@GetMapping("/cost")
	public ResponseEntity<CohortCostResponse> findCohortCost(
			@PathVariable UUID organizationId,
			@RequestParam UUID cohortId,
			@RequestParam(required = false) CohortCostResponse.ClassCostSort sort
	) {
		CohortCostResponse.ClassCostSort effectiveSort =
				sort == null ? CohortCostResponse.ClassCostSort.NAME : sort;
		return ResponseEntity.ok(
				operationsService.findCohortCost(organizationId, cohortId, effectiveSort));
	}
}
