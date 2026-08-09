package com.bigproject.backend.domain.analytics.presentation;

import com.bigproject.backend.domain.analytics.application.ActionRequiredAnalyticsService;
import com.bigproject.backend.domain.analytics.application.CohortComparisonAnalyticsService;
import com.bigproject.backend.domain.analytics.application.GroupGapAnalyticsService;
import com.bigproject.backend.domain.analytics.application.RiskTraineeAnalyticsService;
import com.bigproject.backend.domain.analytics.domain.ComparisonSort;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeLevel;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeSort;
import com.bigproject.backend.domain.analytics.presentation.dto.ActionRequiredResponse;
import com.bigproject.backend.domain.analytics.presentation.dto.CohortComparisonResponse;
import com.bigproject.backend.domain.analytics.presentation.dto.GroupGapResponse;
import com.bigproject.backend.domain.analytics.presentation.dto.RiskTraineeRateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Analytics", description = "기수 분석 격자 조회")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RestController
@RequestMapping(value = "/cohorts/{cohortId}/analytics", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AnalyticsController {

	private final RiskTraineeAnalyticsService riskTraineeAnalyticsService;
	private final CohortComparisonAnalyticsService cohortComparisonAnalyticsService;
	private final ActionRequiredAnalyticsService actionRequiredAnalyticsService;
	private final GroupGapAnalyticsService groupGapAnalyticsService;

	@Operation(
			operationId = "findCohortActionsRequired",
			summary = "조치 필요 경보 조회 | ✅ 사용 가능",
			description = """
					오퍼레이터 대시보드의 `조치 필요` 네 경보를 한 번에 조회합니다. 유형별로 **가장 나쁜 한 건씩**
					올립니다.

					## 요청 (경로 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					| --- | --- | --- | --- |
					| `cohortId` | 필수 | UUID | 분석할 기수 |

					쿼리 파라미터는 없습니다.

					## 응답 (200)

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `cohortId` | UUID | 분석한 기수. 경로 변수를 그대로 반영 |
					| `actionCount` | int | `null`이 아닌 경보 수(0~4). 화면의 `조치 필요 · N건` |
					| `managerUnassigned` | object? | 담당 매니저 미배정. 없으면 `null` |
					| `conceptGap` | object? | 검증 개념 공백. 없으면 `null` |
					| `groupGap` | object? | 집단 미달. 없으면 `null` |
					| `interviewBacklog` | object? | 면담 적체. 없으면 `null` |

					### managerUnassigned (object)

					활성 담당 매니저가 없는 반. **상시 경보라 회차 맥락이 없습니다.**

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `classes[]` | array | 매니저가 없는 반 목록. 반 이름 오름차순 |
					| `affectedTraineeCount` | long | 그 반들에 속한 재학 교육생 합계 |

					#### managerUnassigned.classes[] 각 항목

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `classId` | UUID | 반 ID |
					| `className` | string | 반 이름 |
					| `traineeCount` | long | 이 반의 재학(중도 이탈하지 않은) 교육생 수 |

					### conceptGap (object)

					계획한 개념이 팀 코드에 없어 문제를 만들지 못한 경우.

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `round` | object | 경보가 발생한 회차. 구조는 **RoundRef** 참조 |
					| `teachesId` | UUID | 검증 개념 ID(`teaches.teaches_id`) |
					| `conceptName` | string | 검증 개념 이름 |
					| `gapTeamCount` | long | 그 개념의 코드 근거를 찾지 못한 팀 수 |
					| `participatingTeamCount` | long | 회차에서 이해도 검증 세션이 발생한 팀 수(분모) |

					### groupGap (object)

					반 인원의 절반을 넘는 인원이 한 개념에서 2단 이하인 경우.

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `round` | object | 경보가 발생한 회차. 구조는 **RoundRef** 참조 |
					| `classId` | UUID | 미달이 발생한 반 ID |
					| `className` | string | 미달이 발생한 반 이름 |
					| `teachesId` | UUID | 검증 개념 ID |
					| `conceptName` | string | 검증 개념 이름 |
					| `lowLevelCount` | long | 2단 이하 인원(분자) |
					| `classMemberCount` | long | 반 인원 전체(분모) |

					### interviewBacklog (object)

					예정일이 지났는데 아직 시작되지 않은 면담.

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `round` | object | 경보가 발생한 회차. 구조는 **RoundRef** 참조 |
					| `classId` | UUID | 면담이 적체된 반 ID |
					| `className` | string | 면담이 적체된 반 이름 |
					| `maxDelayDays` | int | 가장 오래 밀린 면담의 지연일 |
					| `pendingInterviewCount` | long | 종결되지 않은 면담 인원. **진행 중을 포함** |
					| `notCreatedCount` | long | 면담이 아직 생성되지 않아 지연일을 계산할 수 없는 후보 수 |
					| `unplannedCount` | long | 예정일이 잡히지 않아 지연일을 계산할 수 없는 면담 수 |

					### RoundRef (object)

					`conceptGap.round` · `groupGap.round` · `interviewBacklog.round`가 공통으로 쓰는 구조입니다.

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `assessmentRoundId` | UUID | 회차 ID(`ProjectAssessmentRound.assessment_round_id`) |
					| `roundNo` | int | 회차 번호. **프로젝트 안에서만 유일** |
					| `roundName` | string | 회차 이름 |
					| `projectId` | UUID | 회차가 속한 프로젝트 ID |
					| `projectName` | string | 프로젝트 이름. 화면의 `미프 3차` |

					⚠️ **`maxDelayDays`와 `pendingInterviewCount`는 모집단이 다릅니다.** 지연일은
					`Interview.planned_at`을 기산점으로 아직 시작되지 않은(`PENDING`) 건만 세지만,
					`pendingInterviewCount`는 진행 중을 포함한 미종결 전체입니다.

					⚠️ **`notCreatedCount`·`unplannedCount`는 경보 판정에서 빠집니다.** 지연일을 계산할 수 없어
					제외되므로 화면에 따로 표시해야 놓치지 않습니다.

					💡 **`conceptGap` 판정은 계산이 아니라 읽기입니다.**
					`AssessmentProblem.generation_status='NOT_GENERATED'`이면 사유가
					`NO_MATCHING_CODE_EVIDENCE` 하나로 고정돼 있습니다.

					💡 **`groupGap`의 분자는 실제 응시를 마친 인원 중 2단 이하만 셉니다.** 미응시·무효 확정은
					0단으로 치환하지 않으므로 분자에서 빠지고 분모(반 인원 전체)에만 남습니다.

					⚠️ **경보가 없으면 그 필드는 `null`입니다.** `actionCount`는 `null`이 아닌 경보의 개수이므로,
					화면은 네 필드를 각각 `null` 검사해서 그려야 합니다.

					💡 **최댓값 한 건만 고르므로 별도 임계값 정책이 없습니다.**

					💡 **경보는 파생 조회이며 해소·무시 상태를 저장하지 않습니다.** 원인이 사라지면 경보도
					사라지므로 조치 완료를 기록하는 쓰기 API가 없습니다.

					💡 **회차 범위는 미니프로젝트로 좁히되 면담 적체만 빅프로젝트 회차도 포함합니다.**
					"""
	)
	@PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "조치 필요 경보 조회 성공"),
			@ApiResponse(responseCode = "401", description = "ANALYTICS_VIEWER_NOT_FOUND 토큰은 유효하지만 계정을 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "ANALYTICS_VIEWER_NOT_ACTIVE 활성 계정 아님 · ANALYTICS_ORGANIZATION_NOT_ACTIVE 소속 기관이 활성 아님 · ANALYTICS_ROLE_NOT_ALLOWED 오퍼레이터·매니저가 아님 · ANALYTICS_COHORT_CROSS_ORGANIZATION 다른 기관의 기수"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND 조회할 기수를 찾을 수 없음")
	})
	@GetMapping("/actions")
	public ResponseEntity<ActionRequiredResponse> findActionsRequired(
			@Parameter(description = "분석할 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		return ResponseEntity.ok(
				actionRequiredAnalyticsService.findActionsRequired(cohortId, authentication.getName()));
	}

	@Operation(
			operationId = "findCohortGroupGaps",
			summary = "집단 미달 목록 조회 | ✅ 사용 가능",
			description = """
					기수 전체에서 **반 인원의 절반을 넘는 인원이 한 검증 개념에서 2단 이하**인 조합을 조회합니다.
					개인 위험 사유가 아니라 반 문제로 분류하며, 시스템은 표시까지만 하고 이후 처리는 기관 판단입니다.

					## 요청 (경로 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					| --- | --- | --- | --- |
					| `cohortId` | 필수 | UUID | 분석할 기수 |

					쿼리 파라미터는 없습니다.

					## 응답 (200)

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `cohortId` | UUID | 분석한 기수 |
					| `underperformanceThresholdRatio` | decimal | 미달 판정 비율 임계값(반 인원 대비) |
					| `lowLevelMaxStage` | int | 저단계로 보는 최대 도달 단계(2단) |
					| `evaluatedClassConceptCount` | int | 평가된 반×개념 조합 수 |
					| `emptyReasonCode` | enum? | `gaps`가 비었을 때의 사유. 미달이 실제 0건이면 `null` |
					| `gaps[]` | array | 미달 조합 목록. 각 항목 구조는 아래 |

					### gaps[] 각 항목

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `assessmentRoundId` | UUID | 회차 ID |
					| `roundNo` | int | 회차 번호 |
					| `roundName` | string | 회차명 |
					| `projectId` | UUID | 프로젝트 ID |
					| `projectName` | string | 프로젝트명 |
					| `classId` | UUID | 반 ID |
					| `className` | string | 반 이름 |
					| `teachesId` | UUID | 검증 개념 ID |
					| `conceptName` | string | 검증 개념명 |
					| `lowLevelCount` | long | 2단 이하 인원(분자) |
					| `classMemberCount` | long | 반 인원 전체(분모) |
					| `lowLevelRate` | decimal | `lowLevelCount / classMemberCount` |

					⚠️ **분자는 실제 응시를 마친 인원 중 2단 이하만 셉니다.** 미응시·무효 확정은 0단으로
					치환하지 않으므로 분자에서 빠지고 **분모(반 인원 전체)에만 남습니다.**

					⚠️ **목록이 비었을 때 `emptyReasonCode`로 두 경우를 구분합니다** — 평가 자체가 없는 경우와
					미달이 실제로 0건인 경우는 다릅니다.

					💡 **발행된 리포트에 의존하지 않고 원천에서 실시간 집계하므로 발행 전에도 값이 나옵니다.**
					"""
	)
	@PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "집단 미달 목록 조회 성공"),
			@ApiResponse(responseCode = "401", description = "ANALYTICS_VIEWER_NOT_FOUND 토큰은 유효하지만 계정을 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "ANALYTICS_VIEWER_NOT_ACTIVE 활성 계정 아님 · ANALYTICS_ORGANIZATION_NOT_ACTIVE 소속 기관이 활성 아님 · ANALYTICS_ROLE_NOT_ALLOWED 오퍼레이터·매니저가 아님 · ANALYTICS_COHORT_CROSS_ORGANIZATION 다른 기관의 기수"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND 조회할 기수를 찾을 수 없음")
	})
	@GetMapping("/group-gaps")
	public ResponseEntity<GroupGapResponse> findGroupGaps(
			@Parameter(description = "분석할 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		return ResponseEntity.ok(groupGapAnalyticsService.findGroupGaps(cohortId, authentication.getName()));
	}

	@Operation(
			operationId = "findCohortRiskTraineeRates",
			summary = "회차별 기수 전체·반별 위험 교육생 비율 조회 | ✅ 사용 가능",
			description = """
					선택 기수의 **미니프로젝트 회차별**로 기수 전체와 반별 위험 교육생 비율을 계산합니다.

					## 요청 (경로 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					| --- | --- | --- | --- |
					| `cohortId` | 필수 | UUID | 분석할 기수 |

					## 요청 (쿼리 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					| --- | --- | --- | --- |
					| `projectId` | 선택 | UUID | 한 미니프로젝트로 좁힌다. 비우면 기수의 모든 미니프로젝트 |
					| `classroomId` | 선택 | UUID[] | 조회할 반 목록. 비우면 기수의 모든 반 |
					| `fromRoundNo` | 선택 | int | 시작 회차. 비우면 1차부터. 최소 `1` |
					| `toRoundNo` | 선택 | int | 종료 회차. 비우면 마지막 회차까지. 최소 `1` |
					| `level` | 선택 | enum | `CLASS`(반, 기본) · `TEAM`(팀) |
					| `sort` | 선택 | enum | `RECENT_ROUND_WORST`(기본) · `WORSE_ROUND_COUNT` · `EXCLUSION_COUNT` · `NAME` |

					⚠️ **`level=TEAM`이면 `projectId`와 `classroomId` 한 건이 모두 필요합니다.** 팀 번호는 반
					안에서만 유일하고 팀은 프로젝트에 종속이라 둘 다 좁혀야 행이 성립합니다. 빠지면 400입니다.

					## 응답 (200)

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `cohortId` | UUID | 분석한 기수 |
					| `projectCategory` | string | 프로젝트 분류. 미니프로젝트만 지원 |
					| `projectId` | UUID? | 좁힌 프로젝트. 지정하지 않았으면 `null` |
					| `totalRegisteredRoundCount` | int | **회차 범위 필터 적용 전** 등록 회차 수. `rounds` 길이와 다를 수 있다 |
					| `appliedSort` | enum | 실제 적용된 정렬 |
					| `level` | enum | 실제 적용된 행 계층 |
					| `rounds[]` | array | 격자의 **열**(회차) 정의 |
					| `cohortSummary` | object | 기수 전체 **행** |
					| `classes[]` | array | 반 **행** 목록 |
					| `teams[]` | array | 팀 **행** 목록. `level=CLASS`이면 `[]` |

					### rounds[] 각 항목 — 격자의 열

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `assessmentRoundId` | UUID | 회차 ID |
					| `roundNo` | int | 회차 번호. **프로젝트 안에서만 유일** |
					| `roundName` | string | 회차 이름 |
					| `projectId` | UUID | 회차가 속한 프로젝트 ID |
					| `projectName` | string | 프로젝트 이름. 화면의 `미프 N차` 기준 |
					| `aggregationStatus` | enum | `NOT_STARTED`(시작 전) · `NOT_AGGREGATED`(리포트 미발행) · `AGGREGATED`(발행 완료) |

					### cohortSummary (object) — 기수 전체 행

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `traineeCount` | long | 중도 이탈하지 않은 현재 기수 교육생 수 |
					| `withdrawnCount` | long | 중도 이탈한 교육생 수 |
					| `exclusionRollup` | object | **최근 발행 회차 기준** 미집계 합계. 구조는 **ExclusionBreakdown** 참조 |
					| `cells[]` | array | 회차별 칸. `rounds`와 **같은 순서·길이** |

					### classes[] 각 항목 — 반 행

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `classId` | UUID | 반 ID |
					| `className` | string | 반 이름 |
					| `traineeCount` | long | 중도 이탈하지 않은 현재 반 교육생 수 |
					| `withdrawnCount` | long | 중도 이탈한 교육생 수 |
					| `exclusionRollup` | object | 최근 발행 회차 기준 미집계 합계. **ExclusionBreakdown** 참조 |
					| `managerNames[]` | string[] | 활성 담당 매니저 이름. 비면 화면의 `담당 없음` |
					| `cells[]` | array | 회차별 칸. `rounds`와 같은 순서·길이 |

					### teams[] 각 항목 — 팀 행

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `teamId` | UUID | 팀 ID |
					| `teamNumber` | string | 반 안에서의 팀 번호 |
					| `teamName` | string | 팀 이름 |
					| `classId` | UUID | 이 팀이 속한 반 ID |
					| `className` | string | 이 팀이 속한 반 이름 |
					| `memberCount` | long | 현재 팀에 속한 인원 |
					| `exclusionRollup` | object | 최근 발행 회차 기준 미집계 합계. **ExclusionBreakdown** 참조 |
					| `cells[]` | array | 회차별 칸. `rounds`와 같은 순서·길이 |

					### cells[] 각 항목 — 격자 한 칸

					`cohortSummary.cells[]` · `classes[].cells[]` · `teams[].cells[]`가 공통으로 쓰는 구조입니다.

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `assessmentRoundId` | UUID | 이 칸이 속한 회차. `rounds[].assessmentRoundId`와 짝 |
					| `roundNo` | int | 회차 번호 |
					| `aggregationStatus` | enum | `NOT_STARTED` · `NOT_AGGREGATED` · `AGGREGATED` |
					| `eligibleCount` | long | 미집계를 제외한 **분모** |
					| `riskCount` | long | 위험 유형을 하나라도 가진 고유 교육생 수(**분자**) |
					| `riskRate` | decimal? | `riskCount / eligibleCount`. 집계 전이거나 분모가 0이면 `null` |
					| `comparisonToCohort` | enum? | `BETTER`(낮음) · `SAME` · `WORSE`(높음). 기준이 없으면 `null` |
					| `exclusion` | object | 이 칸의 미집계 내역. **ExclusionBreakdown** 참조 |

					### ExclusionBreakdown (object) — 미집계 내역

					`cells[].exclusion`과 각 행의 `exclusionRollup`이 공통으로 쓰는 구조입니다.
					세 값을 `eligibleCount`와 합치면 회차의 전체 수행 대상자가 됩니다.

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `notAttendedCount` | long | 미응시(`terminal_reason_code=NOT_ATTENDED`) |
					| `sessionIncompleteCount` | long | 중단(`terminal_reason_code=SESSION_INCOMPLETE`) |
					| `invalidAttemptCount` | long | 무효 응시(`validity_review_status=CONFIRMED_INVALID`) |

					### 분모 (`eligibleCount`)

					회차의 `INITIAL` 수행 대상 교육생에서 **ExclusionBreakdown의 3종**을 뺀 인원입니다.

					⚠️ **무효 확인 중(`validity_review_status=PENDING`)은 아직 확정되지 않아 분모에 남습니다.**
					확정(`CONFIRMED_INVALID`)된 것만 빠집니다.

					### 분자 (`riskCount`)

					위험 유형(단계 하락·지속 저점) 중 **하나 이상이 활성으로 일치한 고유 교육생 수**이며,
					한 교육생이 여러 유형에 해당해도 1명으로 셉니다.

					⚠️ **`INVALID_ATTEMPT`는 분자에 넣지 않습니다.** 해당 교육생이 무효 응시로 분모에서 이미
					빠졌기 때문입니다.

					⚠️ **집계 상태는 회차 생명주기가 아니라 발행된 리포트 유무로 판정합니다.** 발행본이 없으면
					`riskRate`는 0이 아니라 `null`이고 `aggregationStatus`로 원인을 구분합니다.

					⚠️ **`roundNo`는 `(project_id, round_no)` UNIQUE라 프로젝트마다 1부터 다시 시작합니다.**
					미니프로젝트는 프로젝트마다 이해도 확인 회차가 1건뿐이라 모든 열의 `roundNo`가 1입니다.
					기수의 차수 흐름은 프로젝트 순서에만 남으므로 **격자의 가로축은 `roundNo`가 아니라 프로젝트
					순서**이며, `fromRoundNo`·`toRoundNo`도 이 프로젝트 순서 범위입니다. 열을 구분해야 하면
					`roundNo`가 아니라 `assessmentRoundId`나 `projectName`을 쓰십시오.

					⚠️ **`rounds[]`와 각 행의 `cells[]`는 최근 프로젝트부터 내림차순입니다.** 두 배열의 순서와
					길이는 항상 같으므로 인덱스로 짝지어도 됩니다.

					⚠️ **`exclusionRollup`은 조회 범위 전체 회차를 유형별로 합산한 값입니다.** 특정 회차의
					미집계는 `cells[].exclusion`에서 봅니다. `EXCLUSION_COUNT` 정렬은 이 합계 기준입니다.

					💡 **`comparisonToCohort`는 서버가 계산해 내려주므로 클라이언트가 다시 계산할 필요가 없습니다.**
					반 행은 같은 회차의 **기수 전체** 비율과, 팀 행은 **소속 반(`classes[0]`) 전체** 비율과 견준
					방향이며 임계 구간 없이 단순 비교합니다 — 팀 번호는 반 안에서만 유일해 팀끼리는 소속 반
					안에서만 비교가 성립하기 때문입니다. 기수 전체 행이거나 두 비율 중 하나라도 없으면 `null`입니다.

					💡 **`classes[]`에 담기는 범위는 `level`에 따라 다릅니다.** `level=CLASS`이면 기수의 모든 반이,
					`level=TEAM`이면 **선택된 반 1건만** 담깁니다 — 화면 상단 `반 전체` 요약 행이자
					`teams[].cells[].comparisonToCohort`의 비교 기준으로 쓰입니다.

					💡 **팀당 인원이 4~5명이라 팀 행의 비율은 0%·25%·50% 같은 거친 값이 됩니다.**

					💡 **빅프로젝트는 위험 판정식이 달라 이 격자에 포함하지 않습니다.**
					"""
	)
	@PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "위험 교육생 비율 조회 성공"),
			@ApiResponse(responseCode = "400", description = "CLASSROOM_NOT_IN_COHORT 기수에 속하지 않은 반 · PROJECT_NOT_IN_COHORT 기수의 미니프로젝트가 아님 · ROUND_RANGE_INVALID 회차 범위 오류 · TEAM_LEVEL_PROJECT_REQUIRED 팀 계층인데 프로젝트 미지정 · TEAM_LEVEL_SINGLE_CLASSROOM_REQUIRED 팀 계층인데 반이 하나가 아님"),
			@ApiResponse(responseCode = "401", description = "ANALYTICS_VIEWER_NOT_FOUND 토큰은 유효하지만 계정을 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "ANALYTICS_VIEWER_NOT_ACTIVE 활성 계정 아님 · ANALYTICS_ORGANIZATION_NOT_ACTIVE 소속 기관이 활성 아님 · ANALYTICS_ROLE_NOT_ALLOWED 오퍼레이터·매니저가 아님 · ANALYTICS_COHORT_CROSS_ORGANIZATION 다른 기관의 기수"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND 조회할 기수를 찾을 수 없음 · CLASSROOM_NOT_FOUND 팀 계층에서 지정한 반을 찾을 수 없음")
	})
	@GetMapping("/risk-trainees")
	public ResponseEntity<RiskTraineeRateResponse> findRiskTraineeRates(
			@Parameter(description = "분석할 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Parameter(description = """
					조회 대상을 한 미니프로젝트로 좁힙니다. 생략 시 기수의 모든 미니프로젝트를 조회합니다.
					회차 번호는 프로젝트마다 1부터 다시 시작하므로 미니프로젝트가 여러 건인 기수에서
					한 프로젝트의 회차 흐름만 보려면 지정해야 합니다.
					""")
			@RequestParam(required = false) UUID projectId,
			@Parameter(description = "조회할 반 ID 목록이며 생략 시 기수의 모든 반을 조회합니다.")
			@RequestParam(required = false) List<UUID> classroomId,
			@Parameter(description = """
					조회 시작 차수이며 생략 시 1차부터 조회합니다.
					미니프로젝트는 프로젝트마다 이해도 확인 회차가 1건뿐이라 차수는 회차 번호가 아니라
					기수 안의 미니프로젝트 순서를 뜻합니다.
					""", example = "1")
			@RequestParam(required = false) @Min(1) Integer fromRoundNo,
			@Parameter(description = "조회 종료 차수이며 생략 시 마지막 차수까지 조회합니다.", example = "4")
			@RequestParam(required = false) @Min(1) Integer toRoundNo,
			@Parameter(description = """
					행 계층입니다. TEAM이면 projectId와 classroomId 한 건이 모두 필요합니다.
					팀 번호는 반 안에서만 유일하고 팀은 프로젝트에 종속이라 둘 다 좁혀야 행이 성립합니다.
					""", example = "CLASS")
			@RequestParam(required = false, defaultValue = "CLASS") RiskTraineeLevel level,
			@Parameter(description = """
					반·팀 행 정렬 기준입니다.
					RECENT_ROUND_WORST(최근 발행 회차 나쁜 순) / WORSE_ROUND_COUNT(기준보다 나쁜 회차가 많은 순)
					/ EXCLUSION_COUNT(미집계 많은 순) / NAME(이름순)
					""", example = "RECENT_ROUND_WORST")
			@RequestParam(required = false, defaultValue = "RECENT_ROUND_WORST") RiskTraineeSort sort,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		return ResponseEntity.ok(riskTraineeAnalyticsService.findRiskTraineeRates(
				cohortId,
				projectId,
				classroomId,
				fromRoundNo,
				toRoundNo,
				level,
				sort,
				authentication.getName()
		));
	}

	@Operation(
			operationId = "findCohortComparison",
			summary = "두 기수의 검증 개념별 평균 도달 단계 비교 | ✅ 사용 가능",
			description = """
					같은 기관의 두 기수를 **검증 개념(`teaches_id`) 단위**로 맞대어 평균 도달 단계를 비교합니다.
					검증 개념이 기수마다 다르면 같은 프로젝트라도 비교할 수 없으므로 개념 단위로만 맞춥니다.

					## 요청 (경로 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					| --- | --- | --- | --- |
					| `cohortId` | 필수 | UUID | 이번 기수 |

					## 요청 (쿼리 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					| --- | --- | --- | --- |
					| `baselineCohortId` | 선택 | UUID | 비교할 지난 기수. 비우면 **후보 목록만** 반환 |
					| `sort` | 선택 | enum | `WORSENED`(나빠진 순, 기본) · `IMPROVED`(좋아진 순) · `CONCEPT`(검증 개념 순) |
					| `sameCurriculumOnly` | 선택 | boolean | 같은 교안 버전을 쓴 개념만. 기본 `false` |

					💡 **`sameCurriculumOnly=true`이면** 교안이 바뀐 개념을 걸러냅니다 — 평균 차이가 교육생
					변화인지 교안 변화인지 갈라 볼 수 없기 때문입니다. 한쪽 기수에 없던 개념도 함께 제외됩니다.

					## 응답 (200)

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `targetCohort` | object | 이번 기수 |
					| `baselineCohort` | object? | 지난 기수. 미지정이면 `null` |
					| `availableBaselineCohorts[]` | array | 드롭다운 후보 목록 |
					| `emptyStateCode` | enum? | `concepts`가 비었을 때의 사유 |
					| `levelScale` | object | 색 눈금 정의 |
					| `changeThreshold` | object | 변화 판정 임계값 |
					| `appliedSort` | enum | 실제 적용된 정렬 |
					| `sameCurriculumOnly` | boolean | 실제 적용 여부 |
					| `concepts[]` | array | 개념별 비교 행 |

					### targetCohort · baselineCohort (object)

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `cohortId` | UUID | 기수 ID |
					| `cohortName` | string | 기수명 |

					### availableBaselineCohorts[] 각 항목

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `cohortId` | UUID | 후보 기수 ID |
					| `cohortName` | string | 후보 기수명 |
					| `comparable` | boolean | 비교 가능 여부. `false`면 드롭다운에서 비활성 |

					### levelScale (object)

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `min` | int | 최소 단계(1) |
					| `max` | int | 최대 단계(4) |
					| `bandThresholds[]` | decimal[] | 밴드 경계값. 아래 **색 눈금** 표 참조 |

					### changeThreshold (object)

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `worsened` | decimal | 나빠짐 판정 임계값(-0.3) |
					| `improved` | decimal | 좋아짐 판정 임계값(+0.3) |

					### concepts[] 각 항목

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `teachesId` | UUID | 검증 개념 ID |
					| `conceptName` | string | 검증 개념명 |
					| `source` | object | 개념이 나온 교안 위치 |
					| `target` | object | 이번 기수 값. **CohortConceptValue** 참조 |
					| `baseline` | object | 지난 기수 값. **CohortConceptValue** 참조 |
					| `change` | object | 지난 기수 대비 변화 |
					| `curriculumVersion` | object | 교안 버전 변화 |

					#### concepts[].source (object)

					이번 기수 기준이며, 이번 기수에 없으면 지난 기수 기준입니다.
					**개념이 교안에 매핑되지 않았으면 아래 값이 모두 `null`입니다.**

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `curriculumTitle` | string? | 교안 이름 |
					| `sectionSequenceNo` | int? | 교안 장 순번. 화면의 `4장` |
					| `sectionTitle` | string? | 교안 장 제목 |
					| `pageStart` | int? | 개념이 시작되는 쪽수 |
					| `pageEnd` | int? | 개념이 끝나는 쪽수 |
					| `roundNo` | int? | 개념을 검증한 회차 번호. 여러 회차면 **가장 최근** |
					| `roundLabel` | string? | 회차 이름. 화면의 `미프 3차` |

					#### CohortConceptValue (object) — `target` · `baseline` 공통

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `averageReachedLevel` | decimal? | 평균 도달 단계. 개념이 없거나 분모가 0이면 `null` |
					| `levelBand` | int? | 평균이 속한 색 밴드(1~4). 값이 없으면 `null` |
					| `participantCount` | long | 평균의 분모가 된 인원 |
					| `missingCount` | long | 집계에서 빠진 인원 |
					| `presence` | enum | `PRESENT` · `ABSENT_IN_COHORT`(그 기수에 없던 개념) · `MERGED`(다른 개념으로 병합) |
					| `aggregationStatus` | string? | 발행 스냅샷의 집계 상태. 개념이 없으면 `null` |

					#### concepts[].change (object)

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `direction` | enum | `WORSE` · `SIMILAR` · `BETTER` · `NOT_COMPARABLE` |
					| `delta` | decimal? | 이번 기수 평균 − 지난 기수 평균. 비교 불가면 `null` |
					| `notComparableReasonCode` | enum? | 비교할 수 없는 이유. 비교 가능하면 `null` |

					💡 **`delta`는 화면에 보이는 두 평균을 그대로 뺀 값이라 표시값과 항상 일치합니다.**

					#### concepts[].curriculumVersion (object)

					화면의 `v1 → v2` 또는 `v3 · 그대로`입니다.

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `baselineVersionNo` | int? | 지난 기수에서 쓴 교안 버전 |
					| `targetVersionNo` | int? | 이번 기수에서 쓴 교안 버전 |
					| `versionChanged` | boolean | 두 버전이 모두 있고 서로 다르면 `true` |

					### 평균 산식

					발행된 수업 진단 리포트의 **활성 스냅샷**에서 개념별 도달 단계 분포를 읽어
					`Σ(도달 단계 × 인원) / Σ인원`으로 계산합니다.

					⚠️ **응시 인원이 없으면 0이 아니라 `null`입니다.** 낮은 평균은 측정 결과이고,
					`null`은 측정 자체가 없다는 뜻이라 화면에서 구분해 그려야 합니다.

					### 색 눈금 (`levelBand`)

					회차별 위험 비율과 달리 **절대 눈금**이며 목업 색상표와 같은 값입니다. 평균은 연속값이므로
					정수 경계 미만을 **버림(floor)**해 밴드를 배정합니다.

					| 밴드 | 구간 |
					| --- | --- |
					| 1단 | 0 이상 2 미만 |
					| 2단 | 2 이상 3 미만 |
					| 3단 | 3 이상 4 미만 |
					| 4단 | 4 |

					💡 **서버가 `levelBand`와 밴드 경계를 함께 내려주므로 클라이언트가 다시 계산할 필요가 없습니다.**

					### 변화 (`change`)

					이번 기수 평균에서 지난 기수 평균을 뺀 값이며, **-0.3단 이하가 나빠짐 · +0.3단 이상이 좋아짐**입니다.

					⚠️ **다음은 뺄셈이 성립하지 않아 `NOT_COMPARABLE`로 내려갑니다.**
					한쪽 기수에 없는 개념, 다른 개념으로 병합된 개념(`teaches.status=MERGED`),
					반복 개념 집계 산식이 확정되지 않은 개념.

					💡 **`baselineCohortId`를 지정하지 않으면** 드롭다운 후보 목록(`availableBaselineCohorts`)만
					채워 돌려주고 `concepts`는 비어 있습니다.
					"""
	)
	@PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "기수 간 비교 조회 성공"),
			@ApiResponse(responseCode = "400", description = "BASELINE_COHORT_INVALID 비교 대상이 같은 기관의 다른 기수가 아님"),
			@ApiResponse(responseCode = "401", description = "ANALYTICS_VIEWER_NOT_FOUND 토큰은 유효하지만 계정을 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "ANALYTICS_VIEWER_NOT_ACTIVE 활성 계정 아님 · ANALYTICS_ORGANIZATION_NOT_ACTIVE 소속 기관이 활성 아님 · ANALYTICS_ROLE_NOT_ALLOWED 오퍼레이터·매니저가 아님 · ANALYTICS_COHORT_CROSS_ORGANIZATION 다른 기관의 기수"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND 조회할 기수를 찾을 수 없음")
	})
	@GetMapping("/cohort-comparison")
	public ResponseEntity<CohortComparisonResponse> findCohortComparison(
			@Parameter(description = "이번 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Parameter(description = "비교할 지난 기수 ID이며 생략 시 비교 후보 목록만 반환합니다.")
			@RequestParam(required = false) UUID baselineCohortId,
			@Parameter(description = "정렬 기준이며 나빠진 순·좋아진 순·검증 개념 순을 지원합니다.", example = "WORSENED")
			@RequestParam(required = false, defaultValue = "WORSENED") ComparisonSort sort,
			@Parameter(description = """
					같은 교안 버전을 쓴 개념만 남깁니다.
					교안이 바뀌면 평균 차이가 교육생 변화인지 교안 변화인지 갈라 볼 수 없어 걸러냅니다.
					한쪽 기수에 없던 개념도 함께 제외됩니다.
					""", example = "false")
			@RequestParam(required = false, defaultValue = "false") boolean sameCurriculumOnly,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		return ResponseEntity.ok(cohortComparisonAnalyticsService.findCohortComparison(
				cohortId,
				baselineCohortId,
				sort,
				sameCurriculumOnly,
				authentication.getName()
		));
	}
}
