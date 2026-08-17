package com.bigproject.backend.domain.submission.presentation;

import com.bigproject.backend.domain.submission.application.SubmissionStatusService;
import com.bigproject.backend.domain.submission.presentation.dto.ProjectSubmissionStatusResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 매니저가 보는 제출 현황 조회.
 *
 * <p>{@link SubmissionController}에 얹지 않고 컨트롤러를 나눈 이유는 그쪽이 클래스 레벨에서
 * {@code hasRole('TRAINEE')}로 잠겨 있어서다. 메서드마다 권한을 뒤집으면 "이 컨트롤러는 교육생 것"이라는
 * 한 줄짜리 사실이 깨지고, 나중에 추가되는 메서드가 조용히 잘못된 권한을 물려받는다.
 */
@Tag(name = "Submission", description = "교육생 코드 제출과 코드 분석 상태 조회 API")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('MANAGER')")
@Validated
@RestController
@RequiredArgsConstructor
public class ManagerSubmissionStatusController {

	private final SubmissionStatusService submissionStatusService;

	@Operation(
			operationId = "findProjectSubmissionStatus",
			summary = "[프로젝트 상세 - 제출현황 탭] 프로젝트 회차 제출 현황 조회 (매니저) | ✅ 사용 가능",
			description = """
					MG-08 프로젝트 상세 '제출 현황' 탭 한 화면을 **한 번에** 내려준다.

					## 왜 한 덩어리인가

					게이트(팀 편성 단계)·탭 머리 카운트·팀 행·팀원 행·요구사항 판정이 모두 같은
					`(프로젝트, 회차, 반)` 스코프에서 나오고 화면이 그것을 한 번에 그린다. 요구사항을
					따로 떼면 팀 행을 펼칠 때마다 호출이라 팀이 8개면 8번이 되는데, 요구사항은 프로젝트당
					서너 건 고정이라 같이 실어도 응답이 커지지 않는다.

					## 요청

					| 파라미터 | 필수 | 설명 |
					|---|---|---|
					| `projectId` (경로) | 필수 | 조회할 프로젝트 |
					| `roundNo` | 선택 | 회차 번호(기본 1). 프로젝트 안에서만 유일하다 |
					| `classId` | 선택 | 담당 반 하나로 좁힌다. 생략하면 **담당 반 전체**다 |

					🔴 **응답은 호출자가 담당하는 반으로 제한된다.** `classId`를 생략해도 프로젝트 전체가
					아니라 담당 반만 온다. 담당하지 않는 반의 `classId`를 지정하면 빈 결과가 아니라
					`404 MANAGER_SCOPE_NOT_FOUND`다 — 빈 결과로 주면 화면이 "팀이 없는 회차"로 읽는다.

					따라서 `summary`·`teams[]`·`unassignedMemberCount`·`teamFormationStage`는 모두
                    **그 매니저가 보는 범위의 값**이며, 같은 회차라도 매니저마다 다르다.

					## 응답 (200)

					| 필드 | 설명 |
					|---|---|
					| `teamFormationStage` | `NOT_STARTED` · `FORMING` · `READY_TO_CONFIRM` · `CONFIRMED` · `CLOSED` |
					| `submissionOpened` | **제출 현황을 그릴 것이 있는가.** 화면은 이 값만 보고 표/빈 상태를 정한다 — 32차 R11로 기준이 「팀 확정」에서 「제출 수령」으로 바뀌었다(필드 설명 참고) |
					| `locked` | 종료된 회차 |
					| `unassignedMemberCount` | 팀에 배정되지 않은 인원. **제출을 막지는 않는다** — 배정된 팀은 그대로 제출한다 |
					| `summary` | `teamCount` · `submittedTeamCount` · `unsubmittedTeamCount` · `analysisFailedTeamCount` |
					| `requirements[]` | 프로젝트가 정의한 요구사항. **팀이 아니라 프로젝트에 달린 값이라 최상위에 한 번만 싣는다** |
					| `teams[]` | 팀 행. `submission` · `analysis` · `requirementResults[]` · `members[]` |

					### teams[]

					| 필드 | 설명 |
					|---|---|
					| `submission` | 아직 아무도 제출하지 않았으면 **null**. 팀·회차별 최신 제출 한 건이다 |
					| `analysis` | 그 제출에 매인 최신 분석 시도. `status`는 원값(QUEUED·RUNNING·SUCCEEDED·PARTIAL·FAILED) |
					| `requirementResults[]` | (팀, 요구사항)별 최신 판정. 분석 전이면 빈 배열 |
					| `members[]` | 팀원 개인 행 |

					### members[].attendanceStatus

					| 값 | 뜻 |
					|---|---|
					| `DONE` | 응시를 마쳤다 |
					| `OPEN` | 창이 열려 있고 아직 안 봤다 (마감 전) |
					| `MISSED` | 창이 닫히도록 끝내 안 봤다 (마감 후) |
					| `BLOCKED` | 창이 애초에 안 열렸다 — 미제출이거나 분석이 실패했다 |

					⚠️ **`BLOCKED`와 `MISSED`를 합치지 않는다.** `BLOCKED`는 못 본 것이 아니라 볼 수 없었던
					것이라 독촉해도 할 수 있는 일이 없다. 둘을 합치면 화면이 그 사람에게 무엇을 해야 하는지
					말할 수 없다.

					💡 **표시 문구는 서버가 만들지 않는다.** `D-2`·`19시간 남음` 같은 라벨 대신
					`assessmentCloseAt` 시각을 주며, 문구는 화면이 만든다.

					💡 **제출은 팀 단위, 응시는 개인 단위다.** 팀 중 한 명이 내면 팀원 전원이 같은 코드를
					쓰므로 같은 팀에서 제출 상태가 갈릴 수 없고, 그래서 행이 2계층이다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "제출 현황 조회 성공"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED roundNo가 1 미만"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음 · MANAGER_VIEWER_NOT_FOUND 토큰은 유효하지만 계정을 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED 매니저 권한이 아님 · MANAGER_VIEWER_NOT_ACTIVE 활성 계정이 아님 · MANAGER_ROLE_REQUIRED 매니저가 아님"),
			@ApiResponse(responseCode = "404", description = "PROJECT_ROUND_NOT_FOUND 그 프로젝트에 그 번호의 회차가 없음 · MANAGER_SCOPE_NOT_FOUND 담당 범위 밖의 기수이거나 담당하지 않는 반을 classId로 지정함")
	})
	@GetMapping(value = "/projects/{projectId}/submissions", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ProjectSubmissionStatusResponse> findSubmissionStatus(
			@Parameter(description = "조회할 프로젝트 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID projectId,
			@Parameter(description = "조회할 회차 번호이며 프로젝트 안에서만 유일합니다.", example = "1")
			@RequestParam(defaultValue = "1") @Min(1) int roundNo,
			@Parameter(description = "담당 반 하나로 좁힐 때만 지정합니다. 생략하면 담당 반 전체입니다.")
			@RequestParam(required = false) UUID classId,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		return ResponseEntity.ok(submissionStatusService.findSubmissionStatus(
				authentication.getName(), projectId, roundNo, classId));
	}
}
