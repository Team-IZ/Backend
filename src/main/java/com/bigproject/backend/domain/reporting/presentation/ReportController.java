package com.bigproject.backend.domain.reporting.presentation;

import com.bigproject.backend.domain.reporting.application.TraineeReportService;
import com.bigproject.backend.domain.reporting.presentation.dto.TraineeReportsResponse;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Reporting", description = "리포트 발행 이력·본문·문답 조회 API (v2 IA: TR-04 / OP-05)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

	private final TraineeReportService traineeReportService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(
			summary = "내 리포트 전량 조회 | ⚠️ 사용 불가",
			description = """
					TR-04 `내 리포트` 화면 전체를 이 응답 하나로 그린다.
					좌측 회차 레일(`rounds`)과 우측 본문(`reportsById`)이 함께 온다.

					**교육생 본인 것만 나간다.** `traineeId` 파라미터를 받지 않는다 —
					인증 주체로 결정하므로 남의 리포트를 지정할 방법 자체가 없다.

					## 요청

					파라미터 없음. 본문 없음.

					## 응답 — 최상위

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `rounds[]` | array | 좌측 레일. **최신 회차가 앞**이다(서버가 정렬한다) |
					| `reportsById` | object | `rounds[].id`로 찾는 회차별 본문 |

					**rounds[] 각 항목**

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `id` | UUID | 회차 식별자(`assessmentRoundId`). **리포트 id가 아니다** |
					| `label` | string | 회차 이름(예: `미프 3차`) |
					| `hasPendingRetry` | boolean | 아직 안 한 다시 보기가 있다 — 레일에 점으로 표시 |

					## 회차 상태 6종 — `reportsById[id].status`

					| 값 | 언제 | 함께 오는 것 |
					|---|---|---|
					| `NOT_ATTEMPTED` | 응시 기록이 없거나 미제출·미출석으로 끝남 | — |
					| `VOID_ATTEMPT` | 무효 응시(검토 중 또는 무효 확정) | — |
					| `STOPPED` | 세션을 시작했지만 끝내지 못함 | — |
					| `PENDING_PUBLISH` | 아직 발행 전 | `publishAfter` |
					| `PENDING_VISIBILITY` | 발행됐지만 **공개 범위 미지정** | — |
					| `PUBLISHED` | 공개됨 | `publishedAt` · `curriculum` · `concepts[]` · `retryState` |

					⚠️ **`PENDING_VISIBILITY`를 빈 리포트로 그리면 안 된다.** 발행과 공개는 다른
					사건이라, 결과는 이미 확정됐고 공개 범위만 안 정해진 상태다.

					## concepts[] — `PUBLISHED`에서만

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `name` | string | 개념 이름 |
					| `level` | int | 도달 단계 **0~4** |
					| `said` | string? | 학생에게 보여주는 서술 |
					| `isRetryTarget` | boolean | 다시 보기 대상 |
					| `curriculumRef` | object? | `{chapter, pages, title}` — 공개 범위 SUMMARY 이상 |
					| `qa[]` | array? | `{questionLabel, question, answer}` — **공개 범위 FULL에서만** |
					| `explain[]` | array? | 막힌 이유 해설. 다시 보기 대상일 때만 |
					| `comparedReach` | object? | `{before, after}` — 다시 보기를 마쳤을 때만 |

					🔴 **`level` 은 0~4 다섯 단계다.** `0` 은 통과한 축이 하나도 없다는 뜻이며
					**`1`(무엇을 하는지까지 설명함)과 전혀 다르다.** 0을 1로 올려 그리면
					학생에게 사실과 다른 말을 하게 된다.

					`level = 0` 과 "안 물어본 것"도 다르다 — 전자는 물었는데 못 한 것이다.

					## 공개 범위가 응답을 바꾼다

					| 범위 | `said` | `curriculumRef` | `qa[]` |
					|---|---|---|---|
					| `PRIVATE` | ❌ 상태가 `PENDING_VISIBILITY`/비공개 | ❌ | ❌ |
					| `SUMMARY` | ⭕ | ⭕ | ❌ |
					| `FULL` | ⭕ | ⭕ | ⭕ |

					**선택 필드는 키 자체가 빠진다**(null 을 싣지 않는다). `qa` 가 없으면
					`[내 답변] 펼침`을 그리지 않으면 된다.
					"""
	)
	@PreAuthorize("hasRole('TRAINEE')")
	@GetMapping
	public ResponseEntity<TraineeReportsResponse> findMyReports() {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		return ResponseEntity.ok(traineeReportService.findMyReports(userId));
	}

	@Operation(
			summary = "리포트 단건 조회 | ⚠️ 사용 불가",
			description = """
					리포트 1건의 본문. `GET /reports` 응답의 `reportsById[id]` 한 덩어리와 **같은 모양**이다.

					## 화면은 이 API를 쓰지 않는다

					TR-04는 `GET /reports` 한 번으로 전 회차 본문을 받으므로 이 API를 부르지 않는다.
					그래도 두는 이유는 **리포트가 주소를 가진 리소스여야 하기 때문**이다 —
					`GET /reports/{reportId}/disclosure`(공개 범위 조회)가 같은 식별자를 쓴다.

					## 요청

					| 변수 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `reportId` | **필수** | UUID | 리포트 식별자. `assessmentRoundId` 가 아니다 |

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 404 `REPORT_NOT_FOUND` | 없는 리포트 **또는 남의 리포트** |

					⚠️ 남의 리포트를 403이 아니라 **404로 돌려준다.** 403으로 구분해 주면
					"그 id의 리포트가 존재한다"는 사실이 새어 나간다.
					"""
	)
	@PreAuthorize("hasRole('TRAINEE')")
	@GetMapping("/{reportId}")
	public ResponseEntity<TraineeReportsResponse.RoundReportResponse> findMyReport(@PathVariable UUID reportId) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		return ResponseEntity.ok(traineeReportService.findMyReport(userId, reportId));
	}
}
