package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.member.application.TraineeTimelineService;
import com.bigproject.backend.domain.member.presentation.dto.TraineeTimelineResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Member")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RestController
@RequestMapping(value = "/cohorts/{cohortId}/trainees", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('MANAGER')")
@RequiredArgsConstructor
public class TraineeTimelineController {
	private final TraineeTimelineService service;

	@Operation(
			operationId = "findManagerTraineeTimeline",
			summary = "교육생 통합 타임라인 조회 | ✅ 사용 가능",
			description = """
					매니저 교육생 상세(MG-06)의 **이력** 영역을 채운다. 헤더와 회차별 도달 단계 격자는
					별도 호출인 `GET /cohorts/{cohortId}/trainees/{traineeId}`가 담당한다.

					**회차마다 그 회차에서 일어난 사건을 묶어 낸다.** 종전에는 이벤트 목록(`content`)과
					회차 목록(`roundGroups`)이 따로였는데, 팀·기간이 회차 목록에만 있어 화면이 두 배열을
					손으로 맞춰야 했다.

					## 요청 (쿼리 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					| --- | --- | --- | --- |
					| `type` | 선택 | enum | 이벤트 필터. `ASSESSMENT` · `REPORT` · `REVIEW` · `REVIEW_CLOSED` · `INTERVIEW`. 생략하면 `전체` |
					| `cursor` | 선택 | string | 이전 응답의 `nextCursor`. 첫 페이지면 생략 |
					| `size` | 선택 | int | **회차** 수(이벤트 수가 아니다). 기본 `20`, 최대 `100` |

					⚠️ **`size`는 회차 수다.** 회차 중간이 잘리면 화면이 그 회차를 반쪽만 그리므로
					페이지 단위를 이벤트가 아니라 회차로 잡았다. 이벤트 총 수는 `totalElements`에 따로 온다.

					💡 **화면의 `다시 보기` 탭은 두 유형이다.** `REVIEW`(도달이 바뀐 사건)와
					`REVIEW_CLOSED`(창이 닫힐 때까지 안 푼 사건)를 함께 켜면 된다.

					## 응답 (200)

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `totalElements` | int | 필터를 적용한 **전체 이벤트 수**. 화면의 `이벤트 8건` |
					| `rounds[]` | array | 회차 묶음. **최신 차수부터** |
					| `nextCursor`·`hasNext` | | 다음 페이지 |

					### rounds[] — 머리글 `미프 3차 · 1팀 · 07.12 – 07.21`

					| 필드 | 설명 |
					| --- | --- |
					| `cohortRoundNo` | `미프 3차`의 3 |
					| `teamName` | `1팀`. 팀은 프로젝트마다 재편성되므로 회차에 붙는다 |
					| `startAt`·`endAt` | `07.12 – 07.21`. **최초 응시의 응시 창**이다 |
					| `events[]` | 그 회차의 사건들. **일어난 순서(오름차순)** |

					💡 **머리글 우측의 위험 배지(`단계 하락`)는 상세 조회에서 온다.** 같은
					`assessmentRoundId`로 `GET .../trainees/{traineeId}`의 `rounds[].primaryStatusCode`를
					맞추면 된다 — 위험 판정을 두 곳에서 계산하지 않기 위해서다.

					### events[] — 유형별로 채워지는 칸

					| type | 화면 | 채워지는 칸 |
					| --- | --- | --- |
					| `ASSESSMENT` | `이해도 확인 0단 · 1단 · 3단 (재진술 2회)` | `problems[]`의 `reachLevel`·`hintUsedCount`·`conceptName` |
					| `REPORT` | `리포트 발행 다시 보기 2건 지정` | `reviewTargetCount` |
					| `REVIEW` | `다시 보기 HITL Trigger 0단 → 1단` | `reviewChanges[]`의 `conceptName`·`fromReachLevel`·`toReachLevel` |
					| `REVIEW_CLOSED` | `다시 보기 창 마감 Graph 구성 미응시` | `missedConcepts[]`, `reviewDueAt` |
					| `INTERVIEW` | `면담 구현 시간 부족 → 다시 보기 창 안내` | `interview`의 `identifiedCause`·`managerNote`·`managerActions[]` |

					⚠️ **`reachLevel`·`hintUsedCount`의 `null`은 0이 아니다.** 문항이 만들어지지
					않았거나(`NOT_GENERATED`) 한 축도 답하지 않은 경우다. 0단·자력으로 치환하면
					문항을 못 받은 사람이 최하 도달로 보인다. 문항이 없어도 **번호는 남으므로**
					`problems[]`를 순서대로 그리면 격자 칸이 밀리지 않는다.

					⚠️ **`REVIEW`·`REVIEW_CLOSED`는 같은 다시 보기에서 둘 다 나올 수 있다.**
					일부 문항은 풀고 일부는 안 푼 경우이며, 앞은 푼 시각에 뒤는 마감 시각에 놓인다.
					답한 문항이 없으면 `REVIEW`가, 창이 아직 열려 있으면 `REVIEW_CLOSED`가 생기지 않는다.

					💡 **`reviewTargetCount`의 기준은 2단 미만(0~1단)이다.** 정책상 재시험 대상이며
					명단(MG-05) `lowStageConceptCount`와 같은 산식이다 — 2단은 게이트 밖이라 세지 않는다.

					💡 **커서는 회차 차수다.** 손으로 만들지 말고 응답의 `nextCursor`를 그대로 돌려준다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "타임라인 조회 성공"),
			@ApiResponse(responseCode = "400", description = "TIMELINE_CURSOR_INVALID 커서가 올바르지 않음 · VALIDATION_FAILED type에 없는 값을 지정했거나 size 값이 올바르지 않음"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 만료됨"),
			@ApiResponse(responseCode = "403", description = "MANAGER_ROLE_REQUIRED 매니저 권한이 아님 · MANAGER_VIEWER_NOT_ACTIVE 계정·기관이 활성이 아님"),
			@ApiResponse(responseCode = "404", description = "MANAGER_SCOPE_NOT_FOUND 담당 반이 그 기수에 없음")
	})
	@GetMapping("/{traineeId}/timeline")
	public ResponseEntity<TraineeTimelineResponse> findTimeline(
			@Parameter(description = "대상 교육생이 속한 기수 ID") @PathVariable UUID cohortId,
			@Parameter(description = "조회할 교육생의 사용자 ID") @PathVariable UUID traineeId,
			@Parameter(description = "이벤트 유형 필터. 생략하면 전체")
			@RequestParam(required = false) TraineeTimelineResponse.Type type,
			@Parameter(description = "이전 응답의 nextCursor. 첫 페이지면 생략")
			@RequestParam(required = false) String cursor,
			@Parameter(description = "페이지당 회차 수(최대 100). 이벤트 수가 아니다")
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
			@Parameter(hidden = true) Authentication authentication) {
		return ResponseEntity.ok(
				service.findTimeline(authentication.getName(), cohortId, traineeId, type, cursor, size));
	}
}
