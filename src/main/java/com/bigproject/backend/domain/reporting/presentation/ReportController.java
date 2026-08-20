package com.bigproject.backend.domain.reporting.presentation;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.reporting.application.TraineeReportService;
import com.bigproject.backend.domain.reporting.domain.ManagerTraineeAccessRepository;
import com.bigproject.backend.domain.reporting.domain.ReportErrorCode;
import com.bigproject.backend.domain.reporting.domain.ReportException;
import com.bigproject.backend.domain.reporting.presentation.dto.TraineeReportsResponse;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Reporting", description = "리포트 발행 이력·본문·문답 조회 API (v2 IA: TR-04 / OP-05)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping(value = "/reports", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ReportController {

	private final TraineeReportService traineeReportService;
	private final ManagerTraineeAccessRepository managerTraineeAccessRepository;
	private final CurrentUserResolver currentUserResolver;

	@Operation(
			operationId = "findMyReports",
			summary = "내 리포트 전량 조회 | ✅ 사용 가능",
			description = """
					TR-04 `내 리포트` 화면 전체를 이 응답 하나로 그린다.
					좌측 회차 레일(`rounds`)과 우측 본문(`reportsById`)이 함께 온다.

					> **2026-08-12 사용 가능으로 올림(16차 R4).** 조회 SQL이 읽는 뷰·컬럼이 정본 문서에
					> 있는지를 `TraineeReportCanonicalSqlContractTest`가 확인한다. 라이브 DB 없이 확인할 수
					> 있는 최대치가 여기까지이며, 조인 결과의 의미론은 이 테스트가 잡지 못한다.

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

					## 회차 상태 7종 — `reportsById[id].status`

					**(2026-08-20 정정)** 아래 표는 실제와 어긋나 있었다 — `PENDING_VISIBILITY`는
					공개/비공개 폐지(2026-08-19)로 이미 없어졌고, `NOT_STARTED`(마감 전 미응시)가
					빠져 있었다. 같은 날 `IN_PROGRESS`도 새로 추가됐다(아래 참고).

					| 값 | 언제 | 함께 오는 것 |
					|---|---|---|
					| `NOT_STARTED` | 제출 마감 전인데 아직 응시 기록이 없다 | — |
					| `NOT_ATTEMPTED` | 마감이 지나도록 응시하지 않았거나 미제출·미출석으로 끝남 | — |
					| `VOID_ATTEMPT` | 무효 응시(검토 중 또는 무효 확정) | — |
					| `STOPPED` | 세션을 시작했지만 끝내지 못함 | — |
					| `IN_PROGRESS` | **응시 기록은 있지만 아직 안 끝났다**(제출·분석·이해도 확인 세션 준비/진행 중, 2026-08-20 추가) | — |
					| `PENDING_PUBLISH` | **이해도 확인까지 마쳤고** 아직 발행 전 | `publishAfter` |
					| `PUBLISHED` | 공개됨 | `publishedAt` · `curriculum` · `concepts[]` · `retryState` |

					## 🔴 `IN_PROGRESS` 추가 배경 (2026-08-20 발견·수정)

					고치기 전에는 `NOT_STARTED`·`NOT_ATTEMPTED`·`VOID_ATTEMPT`·`STOPPED` 넷 중 어디에도
					안 걸리는 진행 중인 응시(코드 제출·분석·이해도 확인 세션 준비 단계)가 전부
					`PENDING_PUBLISH`로 떨어졌다 — **아직 응시조차 시작 안 했거나 절반쯤 온 회차가
					"응시 완료(리포트 생성 중)"로 보이는 상태**였다(실사용 재현: 코드 분석이 진행 중인
					회차가 화면에 "응시 완료"로 뜸). `PENDING_PUBLISH`는 이제 **이해도 확인까지 실제로
					마친** 경우로만 좁혔고, 그 전 단계는 `IN_PROGRESS`다.

					## concepts[] — `PUBLISHED`에서만

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `name` | string | 개념 이름 |
					| `asked` | boolean | **물었는가.** `false`면 아래 값이 전부 빠진다 |
					| `level` | int? | 도달 단계 **0~4**. `asked=false`면 키가 빠진다 |
					| `said` | string? | 학생에게 보여주는 서술 |
					| `isRetryTarget` | boolean | 다시 보기 대상 |
					| `curriculumRef` | object? | `{chapter, pages, title}` — 공개 범위 SUMMARY 이상 |
					| `qa[]` | array? | `{questionLabel, question, answer}` — **공개 범위 FULL에서만** |
					| `explain[]` | array? | 막힌 이유 해설. 다시 보기 대상일 때만 |
					| `comparedReach` | object? | `{before, after}` — 다시 보기를 마쳤을 때만 |

					🔴 **`level` 은 0~4 다섯 단계다.** `0` 은 통과한 축이 하나도 없다는 뜻이며
					**`1`(무엇을 하는지까지 설명함)과 전혀 다르다.** 0을 1로 올려 그리면
					학생에게 사실과 다른 말을 하게 된다.

					### 🔴 문항 없음 — 제3의 값 (`asked: false`)

					개념 3개 중 하나가 그 학생 코드에 없으면 문항이 만들어지지 않는다
					(`assessment_problem.generation_status='NOT_GENERATED'`). 그 개념도 **`concepts[]`에
					같이 들어오며** `asked=false`이고 `level`을 포함한 판정 필드가 전부 빠진다.

					**`level=0`과 합치면 안 된다.** 전자는 물었는데 통과한 축이 없는 것이고, 후자는 묻지
					않은 것이다. `GET /reports/class-diagnosis`가 `level0`과 `unasked`를 엄격히 구분하는
					것과 같은 규칙이다.

					⚠️ **`asked=false`는 다시 보기 대상이 아니다** — 다시 볼 문항이 없기 때문이다.
					`isRetryTarget`은 항상 `false`로 온다.

					> 이 개념들을 배열에서 빼지 않는 이유는, 빼면 화면에 개념이 2개만 뜨고 학생이 나머지
					> 하나가 어디 갔는지 알 수 없기 때문이다. *"코드에 이 개념이 없어 묻지 못했습니다 —
					> 못한 것이 아닙니다"* 를 그 자리에 그릴 수 있어야 한다.

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
			operationId = "findManagedTraineeReports",
			summary = "담당 교육생 리포트 조회 (매니저) | ✅ 사용 가능",
			description = """
					매니저가 담당 교육생 **한 명**의 리포트를 회차별로 본다. 교육생 상세 화면의 리포트 라인이다.

					## 응답이 `GET /reports`와 똑같다

					같은 `TraineeReportsResponse`다 — 좌측 레일(`rounds`)과 회차별 본문(`reportsById`)이
					그대로 온다. **모양을 따로 두지 않은 것은 의도한 것이다.** 리포트 라인은 회차별로
					"만들어졌나 · 발행됐나"를 보여주고 펼치면 본문을 그리는데, 그 본문이 학생이 보는
					것과 달라야 할 이유가 없다. 두 벌로 나누면 같은 리포트를 설명하는 말이 두 가지가 된다.

					회차 상태(`status`)도 같은 값을 쓴다.

					| 값 | 리포트 라인에 그릴 것 |
					|---|---|
					| `PUBLISHED` | 결과를 그린다(펼치면 본문) |
					| `PENDING_PUBLISH` | 이해도 확인까지 마침, `리포트 생성 중` |
					| `IN_PROGRESS` | 아직 응시가 안 끝남(제출·분석·이해도 확인 세션 준비/진행 중, 2026-08-20 추가) — `PENDING_PUBLISH`와 구분해서 그린다 |
					| `NOT_STARTED` | 아직 응시 전(마감 전) |
					| `NOT_ATTEMPTED` | 미응시 — **매니저 안내가 필요한 줄이다** |
					| `VOID_ATTEMPT` | 확인 필요 |
					| `STOPPED` | 중단 |

					## 🔴 다시 보기 잠금이 걸리지 않는다

					교육생 화면에서는 다시 보기 대상(`level < 2`)이면서 아직 다시 보기를 마치지 않은
					개념의 `qa`(자기 답변)와 `explain`(해설)이 **빠진다.** 매니저에게는 그 잠금을 걸지
					않는다 — 잠금의 목적이 "학생이 답을 먼저 보고 다시 푸는 것"을 막는 것이라
					매니저에게는 해당이 없고, 지도하려면 학생이 뭐라고 답했는지를 봐야 한다.

					`retryState`는 **사실대로** 나간다(`NONE` · `PENDING` · `DONE`).
					다시 보기를 아직 안 한 학생을 찾는 근거이므로 가리지 않는다.

					## 요청

					| 파라미터 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `traineeId` | **필수** | UUID | 교육생 `userId`. 회차 id도 리포트 id도 아니다 |

					## 권한 — 지금 담당 중인 교육생만

					요청 매니저가 **지금** 배정된 반의 교육생이어야 한다. 해제된 배정
					(`manager_assignment.unassigned_at`)과 이탈한 교육생(`cohort_member.left_at`)은
					담당으로 치지 않는다 — 그러지 않으면 지난 기수에 잠깐 담당했던 매니저가
					계속 남의 교육생 리포트를 본다.

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 404 `REPORT_NOT_FOUND` | 없는 교육생 **또는 담당하지 않는 교육생** |

					⚠️ 담당하지 않는 교육생을 403이 아니라 **404로 돌려준다.** 403으로 구분해 주면
					"그 id의 교육생이 존재한다"는 사실이 새어 나간다(`GET /reports/{reportId}`와 같은 규칙).

					빈 `rounds`는 정상이다 — 그 교육생이 속한 기수에 회차가 아직 없는 경우다.
					"""
	)
	@PreAuthorize("hasRole('MANAGER')")
	// `/{reportId}`보다 구체적인 리터럴이라 스프링이 이 경로를 먼저 고른다 — `managed`가
	// UUID로 파싱되는 일은 없다. CohortReportController의 `/reports/class-diagnosis`도 같은 구조다.
	@GetMapping("/managed")
	public ResponseEntity<TraineeReportsResponse> findManagedTraineeReports(@RequestParam UUID traineeId) {
		AuthUser manager = currentUserResolver.resolveCurrentUser();
		if (!managerTraineeAccessRepository.isManagedBy(traineeId, manager.userId(), manager.organizationId())) {
			// 담당이 아니면 404다. 존재 여부를 알려 주지 않기 위해 "없는 교육생"과 같은 응답을 쓴다.
			throw new ReportException(ReportErrorCode.REPORT_NOT_FOUND);
		}
		return ResponseEntity.ok(traineeReportService.findTraineeReportsForManager(traineeId));
	}

	@Operation(
			operationId = "findMyReport",
			summary = "리포트 단건 조회 | ✅ 사용 가능",
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
