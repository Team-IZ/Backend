package com.bigproject.backend.domain.projectexecution.presentation;

import com.bigproject.backend.domain.projectexecution.application.ClassProgressService;
import com.bigproject.backend.domain.projectexecution.presentation.dto.ClassProgressResponse;
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

import java.util.UUID;

@Tag(name = "Project", description = "프로젝트 진행 현황 조회")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RestController
@RequestMapping(value = "/projects/{projectId}", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ProjectController {

	private final ClassProgressService classProgressService;

	@Operation(
			operationId = "findProjectClassProgress",
			summary = "반별 제출·분석·응시 현황 조회 | ✅ 사용 가능",
			description = """
					프로젝트 회차의 반별 진행을 제출 → 분석 → 응시 순으로 한 번에 조회합니다.
					대시보드 '이번 회차' 카드와 프로젝트 상세 '현황' 화면이 함께 쓰는 API입니다.

					단계별 깔때기라 각 단계의 분모가 앞 단계의 분자입니다.
					제출률은 submittedCount / targetTraineeCount,
					응시율은 assessedCount / analysisSucceededCount 입니다.
					응시율의 분모가 제출 단계에서 나오므로 두 지표를 나눠 호출하지 않습니다.

					제출은 팀 단위 원장이지만 이 화면은 인원 기준으로 환산합니다.

					분석 상태는 성공·실패·부분 성공·진행 중 네 갈래를 모두 내려줍니다.
					부분 성공(PARTIAL)은 분석 완료로 세지 않으므로 응시율 분모에서 빠집니다.
					네 값을 더하면 제출 인원과 같아 어느 열에도 잡히지 않고 사라지는 인원이 없습니다.

					회차는 projectId와 roundNo로 특정합니다. round_no는 프로젝트 안에서만 유일합니다.

					## 요청

					| 파라미터 | 위치 | 필수 | 타입 | 설명 |
					|---|---|---|---|---|
					| `projectId` | 경로 | **필수** | UUID | 조회할 프로젝트 ID |
					| `roundNo` | 쿼리 | 선택(기본 `1`) | int | 조회할 회차 번호. 프로젝트 안에서만 유일하며 1 미만이면 400 |

					## 응답 — 최상위

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `projectId` | UUID | 조회한 프로젝트 |
					| `projectName` | string | 프로젝트 이름 |
					| `assessmentRoundId` | UUID | 이 회차의 내부 식별자 |
					| `roundNo` | int | 회차 번호(프로젝트 안에서만 유일) |
					| `roundName` | string | 회차 이름 |
					| `totalRoundCount` | int | 이 프로젝트에 속한 전체 회차 수. 화면의 'N차 / M회' 표기에 씁니다 |
					| `submissionDueAt` | timestamp | 제출 마감 시각 |
					| `reportPublishMode` | enum | 리포트 발행 방식. 현재는 마감 후 전 반을 한 번에 발행하는 `ROUND_BATCH`만 존재 |
					| `reportPublished` | boolean | 이 회차 리포트가 발행됐는지 여부. `false`면 화면에 '미발행'을 표시 |
					| `summary` | object | 회차 전체 진행 현황 합계. classes[]를 합산한 값이 아니라 회차 단위로 직접 집계 |
					| `classes[]` | array | 반 행 목록. 반 이름 오름차순 |
					| `conceptMatches[]` | array | 검증 개념별 코드 매칭 현황 |

					**`summary`** — 회차 전체 합계

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `targetTraineeCount` | long | 이번 회차 수행 대상 교육생 수(기수 총원). 제출률의 분모 |
					| `submittedCount` | long | 제출을 마친 교육생 수 |
					| `analysisTargetCount` | long | 분석 대상 교육생 수. `submittedCount`와 값이 같습니다 — 단계별 분모를 필드 이름으로도 드러내려고 따로 둡니다 |
					| `analysisSucceededCount` | long | 분석이 성공한 교육생 수 |
					| `assessmentTargetCount` | long | 응시 대상 교육생 수. `analysisSucceededCount`와 값이 같습니다 |
					| `assessedCount` | long | 응시(INITIAL 완료)를 마친 교육생 수 |

					**`classes[]`** — 반 한 행

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `classId` | UUID | 반 식별자 |
					| `className` | string | 반 이름 |
					| `targetTraineeCount` | long | 회차 수행 대상 교육생 수. 제출률의 분모 |
					| `submittedCount` | long | 소속 팀이 제출을 마친 교육생 수 |
					| `analysisSucceededCount` | long | 분석이 성공한 교육생 수. 응시율의 분모이며 PARTIAL은 포함하지 않음 |
					| `analysisFailedCount` | long | 분석이 실패한 교육생 수(**인원** 기준) |
					| `analysisPartialCount` | long | 분석이 부분 성공(PARTIAL)한 교육생 수. 응시율 분모에서 빠짐 |
					| `analysisInProgressCount` | long | 분석이 대기·진행 중인 교육생 수 |
					| `assessedCount` | long | 최초 응시(INITIAL)를 완료한 교육생 수 |
					| `notAttendedCount` | long | 미응시(NOT_ATTENDED) 교육생 수 |
					| `sessionIncompleteCount` | long | 중단(SESSION_INCOMPLETE) 교육생 수 |
					| `invalidAttemptCount` | long | 무효 확정(CONFIRMED_INVALID) 교육생 수. 무효 확인 중(PENDING)은 미포함 |
					| `managerNames[]` | string[] | 활성 담당 매니저 이름 목록. 비어 있으면 화면의 '담당 없음'이며 대시보드 미배정 경보와 같은 조건 |
					| `failedTeams[]` | array | 분석이 실패한 팀 목록(**팀** 기준). 크기가 `analysisFailedCount`(인원 기준)와 다를 수 있습니다 — 한 팀에 팀원이 여럿이면 인원 수가 더 큽니다 |

					submittedCount = analysisSucceededCount + analysisFailedCount + analysisPartialCount + analysisInProgressCount

					**`classes[].failedTeams[]`** — 분석 실패 팀 한 건. 팀·회차별 최신 제출과 그 제출에 매인 최신 분석 시도만 봅니다(재시도 반영)

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `teamId` | UUID | 팀 식별자 |
					| `teamName` | string | 팀 이름 |
					| `representativeUserId` | UUID | 대표자(그 팀·회차의 최신 제출을 실행한 사용자) ID |
					| `representativeName` | string | 대표자 이름 |
					| `failureReason` | string? | `analysis_job.failure_reason` 원문. null일 수 있음 |

					**`conceptMatches[]`** — 검증 개념별 코드 매칭 한 행. 문제는 팀 공용이라 팀 단위 매칭 판정을 팀원 인원으로 펼쳐 셉니다

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `teachesId` | UUID | 검증 개념 식별자 |
					| `conceptName` | string | 검증 개념 이름 |
					| `analysedTraineeCount` | long | 분석에 성공한 교육생 수. 매칭률의 분모 |
					| `matchedTraineeCount` | long | 그 개념의 문제를 받은 교육생 수 |
					| `unmatchedTeamCount` | long | 그 개념이 코드에서 발견되지 않아 전원이 문제를 받지 못한 팀 수 |
					"""
	)
	@PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "반별 현황 조회 성공"),
			@ApiResponse(responseCode = "400", description = "ROUND_NO_INVALID 회차 번호가 1 미만"),
			@ApiResponse(responseCode = "401", description = "ANALYTICS_VIEWER_NOT_FOUND 토큰은 유효하지만 계정을 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "ANALYTICS_VIEWER_NOT_ACTIVE 활성 계정 아님 · ANALYTICS_ORGANIZATION_NOT_ACTIVE 소속 기관이 활성 아님 · ANALYTICS_ROLE_NOT_ALLOWED 오퍼레이터·매니저가 아님 · PROJECT_CROSS_ORGANIZATION 다른 기관의 프로젝트"),
			@ApiResponse(responseCode = "404", description = "PROJECT_ROUND_NOT_FOUND 그 프로젝트에 그 번호의 회차가 없음")
	})
	@GetMapping("/class-progress")
	public ResponseEntity<ClassProgressResponse> findClassProgress(
			@Parameter(description = "조회할 프로젝트 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID projectId,
			@Parameter(description = "조회할 회차 번호이며 프로젝트 안에서만 유일합니다.", example = "1")
			@RequestParam(defaultValue = "1") @Min(1) int roundNo,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		return ResponseEntity.ok(
				classProgressService.findClassProgress(projectId, roundNo, authentication.getName()));
	}
}
