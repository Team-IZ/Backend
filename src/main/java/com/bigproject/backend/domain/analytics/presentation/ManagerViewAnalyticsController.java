package com.bigproject.backend.domain.analytics.presentation;

import com.bigproject.backend.domain.analytics.application.ManagerViewAnalyticsService;
import com.bigproject.backend.domain.analytics.presentation.dto.ConceptScopeResponse;
import com.bigproject.backend.domain.analytics.presentation.dto.ManagerHeatmapResponse;
import com.bigproject.backend.domain.analytics.presentation.dto.RiskSignalResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Analytics", description = "기수 분석 격자 조회")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping(value = "/cohorts/{cohortId}/analytics", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('MANAGER')")
@RequiredArgsConstructor
public class ManagerViewAnalyticsController {

	private final ManagerViewAnalyticsService service;

	@Operation(
			operationId = "findManagerHeatmap",
			summary = "매니저 히트맵 조회 | ✅ 사용 가능",
			description = """
                회차 × 검증 개념 격자를 반·팀·팀원 세 계층으로 조회한다.
                **계층이 달라도 응답 모양이 같다** — `rows[]`가 언제나 그 계층의 비교 단위이고
                고정된 상위 계층은 `scope`가 한 번만 싣는다.

                ## 요청 (쿼리 파라미터)

                | 파라미터 | 필수 | 설명 |
                |---|---|---|
                | `projectId` | 필수 | 조회할 프로젝트 |
                | `assessmentRoundId` | 필수 | 조회할 회차. 열(검증 개념)이 회차마다 다르다 |
                | `level` | 필수 | `CLASS` · `TEAM` · `TRAINEE` |
                | `attemptView` | 선택 | `INITIAL`(기본) · `REVIEW`. `REVIEW`는 `TRAINEE`만 허용 |
                | `classroomId` | 조건부 | `TEAM`·`TRAINEE`에서 **필수**, `CLASS`에서는 지정하면 400 |
                | `teamId` | 조건부 | `TRAINEE`에서 **필수**, 그 외에는 지정하면 400 |

                ## 응답 (200)

                | 필드 | 설명 |
                |---|---|
                | `asOfAt` | 집계 시각. **응답 전체의 성질**이라 셀마다 싣지 않는다 |
                | `scope` | 고정된 상위 계층. `CLASS`는 고정 상위가 없어 `null` |
                | `concepts[]` | 가로축(열 머리). `problemNo` · `teachesId` · `conceptName` · `groupShortfall` |
                | | ↳ `problemNo`는 **열 순번(1..N)**이다. 문제 순번이 아니다 — 아래 ⚠️ 참고 |
                | `summary` | 화면 상단 합계 행. `rowId`·`rowName`은 `null` |
                | `rows[]` | 세로축. `rowId`·`rowName`·`memberCount`·`cells[]` |
                | `navigation` | 툴바 셀렉터용 반·팀 목록. **`CLASS`에서는 비운다**(`rows`와 같은 값) |

                ### rows[].cells[]

                | 필드 | 설명 |
                |---|---|
                | `problemNo`·`teachesId` | 같은 순번의 `concepts[]`와 짝이 되는 **열 순번·개념** |
                | `value` | 집계 행은 **평균**, 개인 행은 **도달 단계 원값**(0~4) |
                | `validCount`·`notAttendedCount`·`invalidCount`·`interruptedCount` | 결과 구분별 인원 |
                | `status` | 집계 상태 또는 개인 응시 결과 상태. `NOT_GENERATED`(미출제) 포함 |
                | `groupShortfall` | 집단 미달. **반 행에만** 채운다 |
                | `initialLevel`·`comparisonLevel`·`delta` | `REVIEW` 전용. `INITIAL`에서는 **키 자체가 빠진다** |

                ⚠️ **미출제 개념도 열이 선다.** 코드에 근거가 없어 문항이 만들어지지 않은
                개념(`NOT_GENERATED`)은 검증 세션에서 물을 수 없지만, 그 칸을 비워 두면 「물었는데
                결과가 없다」와 구분되지 않습니다. 그런 칸은 `status = "NOT_GENERATED"`이고
                `value`·`validCount`·`notAttendedCount`·`invalidCount`·`interruptedCount`가
                **모두 `null`**입니다. 팀마다 다릅니다 — 같은 열이라도 어떤 팀은 출제됐고 어떤 팀은
                미출제일 수 있어, 그 열의 반 평균은 출제된 인원만으로 냅니다.

                ⚠️ **`rows[].cells[]`는 언제나 `concepts[]`와 같은 길이·같은 순서다.** 그 개념에 결과가
                없는 자리도 빈 셀로 채워 나가므로, 화면은 두 배열을 순번으로 짝지어도 되고 `teachesId`로
                짝지어도 된다.

                ⚠️ **`problemNo`는 열 순번이지 문제 순번이 아니다.** 가로축은 검증 개념(`teachesId`)이다.
                문제 순번(`assessment_problem.problem_no`)은 팀 분석마다 다시 1부터 매겨져 한 회차 안에서도
                팀에 따라 1번이 가리키는 개념이 다르므로 축이 될 수 없다. 개념의 식별자는 `teachesId`이고,
                드릴다운(`GET .../concepts/{teachesId}`)도 같은 값을 쓴다.

                ⚠️ **`memberCount`는 명부 인원이라 `validCount`와 다르다.** 응시하지 않은 사람을 포함한다.

                ⚠️ **`summary`는 개인 단위 가중 평균이다.** 반별 평균을 다시 평균 내면 인원이 다른 반이
                같은 무게가 되어 값이 달라지므로 화면에서 `rows`를 평균 내지 않는다.

                💡 **집단 미달은 유효 응시자 기준이다** — 유효 응시자의 절반을 **넘는** 인원이 2단 이하일 때
                참이며 미응시·무효 확정·중단은 분모에서 뺀다. 면담 브리프의 개념 소관 판정과 같은 산식이다.

                💡 **팀 행에는 `groupShortfall`을 달지 않는다.** 팀은 3~4명이라 한 사람이 판정을 뒤집는다.
                """
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "격자 조회 성공"),
			@ApiResponse(responseCode = "400", description = "HEATMAP_SCOPE_INVALID 계층과 classroomId·teamId 조합이 맞지 않거나 **teamId가 이 회차의 팀이 아님**(32차 R13) · HEATMAP_REVIEW_TRAINEE_REQUIRED REVIEW는 TRAINEE 계층만 허용"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 만료됨"),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED 매니저 권한이 아님"),
			@ApiResponse(responseCode = "404", description = "MANAGER_SCOPE_NOT_FOUND 담당 범위 밖의 기수이거나 존재하지 않음")
	})
	@GetMapping("/heatmap")
	public ResponseEntity<ManagerHeatmapResponse> findHeatmap(
			@PathVariable UUID cohortId, @RequestParam UUID projectId,
			@RequestParam UUID assessmentRoundId, @RequestParam ManagerHeatmapResponse.Level level,
			@RequestParam(defaultValue = "INITIAL") ManagerHeatmapResponse.AttemptView attemptView,
			@RequestParam(required = false) UUID classroomId, @RequestParam(required = false) UUID teamId,
			Authentication authentication) {
		return ResponseEntity.ok(service.findHeatmap(authentication.getName(), cohortId, projectId,
				assessmentRoundId, level, attemptView, classroomId, teamId));
	}

	@Operation(operationId = "findManagerConceptScope", summary = "면담 브리프 개념 소관 판정 | ✅ 사용 가능")
	@GetMapping("/concept-scope")
	public ResponseEntity<ConceptScopeResponse> findConceptScope(
			@PathVariable UUID cohortId, @RequestParam UUID assessmentRoundId,
			@RequestParam UUID classroomId, @RequestParam UUID teachesId, Authentication authentication) {
		return ResponseEntity.ok(service.findConceptScope(authentication.getName(), cohortId,
				assessmentRoundId, classroomId, teachesId));
	}

	// =========================================================================
	// MG-01 인박스 행 근거 판정용: 위험 신호 조회
	// (구 /risk-signals와 로직 중복이라 통합함 — 5번 정리. assessmentRoundId·reasonCode는
	// 구 /risk-signals가 갖고 있던 필터를 선택값으로 흡수했다.)
	// =========================================================================
	@Operation(
			operationId = "getRiskSignalsForInbox",
			summary = "위험 신호 조회 (인박스 행 근거) | ✅ 사용 가능",
			description = """
					매니저 인박스·면담 목록·교육생 상세가 공용으로 쓰는 위험 신호 원장을 조회한다.
					**행 근거를 채우는 조회라 화면마다 파라미터 조합만 다르다** — 새 화면이 생겨도
					조회 로직은 여기 하나다.

					## 요청 (경로 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `cohortId` | **필수** | UUID | 조회할 기수 |

					## 요청 (쿼리 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `classId` | 선택 | UUID | 반으로 좁힌다. MG-01 인박스가 사용 |
					| `traineeId` | 선택 | UUID | 교육생 한 명으로 좁힌다. MG-06 교육생 상세(지속 저점)가 사용 |
					| `assessmentRoundId` | 선택 | UUID | 회차로 좁힌다. **지금 화면 중 쓰는 곳은 없다** — 구 `/risk-signals`가 갖던 필터를 유지만 한다 |
					| `reasonCode` | 선택 | string | 위험 신호 사유 코드로 좁힌다. **지금 화면 중 쓰는 곳은 없다** — 위와 같은 이유 |

					모든 쿼리 파라미터는 조합해서 쓸 수 있으며, 전부 생략하면 그 기수의 위험 신호
					전체를 돌려준다.

					## 응답 (200)

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `cohortId` | UUID | 조회한 기수. 경로 변수를 그대로 반영 |
					| `signals[]` | array | 위험 신호 목록 |

					### signals[] 각 항목

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `signalId` | UUID | 신호 식별자 |
					| `reasonCode` | string | 위험 신호 사유 |
					| `assessmentRoundId` | UUID | 신호가 발생한 회차 |
					| `classroomId` | UUID | 신호가 발생한 반 |
					| `teamId` | UUID | 신호가 발생한 팀 |
					| `traineeId` | UUID | 대상 교육생 |
					| `traineeName` | string | 대상 교육생 이름 |
					| `summary` | string | 신호 요약 문구 |
					| `status` | string | 신호 상태 |
					| `policyVersion` | int | 판정에 쓰인 정책 버전 |
					| `detectedAt` | timestamp | 신호가 감지된 시각 |

					💡 **구 `/risk-signals`와 로직이 중복이라 이 엔드포인트로 통합했다.** 두 엔드포인트가
					같은 리포지토리 조회를 호출하고 있었고, `/risk-signals`는 문서·에러 응답이 없는
					미완성 상태로 방치돼 있었다. `assessmentRoundId`·`reasonCode`는 그쪽이 갖고
					있던 필터를 선택값으로 그대로 옮겨온 것이다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "위험 신호 조회 성공"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 만료됨"),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED 매니저 권한이 아님"),
			@ApiResponse(responseCode = "404", description = "MANAGER_SCOPE_NOT_FOUND 담당 범위 밖의 기수이거나 존재하지 않음"),
	})
	@GetMapping("/signals")
	public ResponseEntity<RiskSignalResponse> getRiskSignalsForInbox(
			@Parameter(description = "조회할 기수 ID") @PathVariable UUID cohortId,
			@Parameter(description = "반으로 좁힌다. 생략하면 반 구분 없이 조회") @RequestParam(required = false) UUID classId,
			@Parameter(description = "교육생 한 명으로 좁힌다") @RequestParam(required = false) UUID traineeId,
			@Parameter(description = "회차로 좁힌다. 지금 화면은 쓰지 않는다") @RequestParam(required = false) UUID assessmentRoundId,
			@Parameter(description = "위험 신호 사유 코드로 좁힌다. 지금 화면은 쓰지 않는다") @RequestParam(required = false) String reasonCode,
			Authentication authentication) {

		RiskSignalResponse response = service.getRiskSignalsForInbox(
				authentication.getName(), cohortId, classId, traineeId, assessmentRoundId, reasonCode);
		return ResponseEntity.ok(response);
	}
}