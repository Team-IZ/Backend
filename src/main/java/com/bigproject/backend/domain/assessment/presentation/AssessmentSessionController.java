package com.bigproject.backend.domain.assessment.presentation;

import com.bigproject.backend.domain.assessment.application.AssessmentSessionService;
import com.bigproject.backend.domain.assessment.presentation.dto.AnswerSubmitRequest;
import com.bigproject.backend.domain.assessment.presentation.dto.AnswerSubmitResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.SessionActivityRequest;
import com.bigproject.backend.domain.assessment.presentation.dto.HintResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.ProblemActivityResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.SessionResponse;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 검증 세션(TR-03). 전체화면 · 네비 없음 · 나가는 경로 없음.
 *
 * <h2>경로가 다섯인 이유</h2>
 *
 * <p>세션 하나에 필요한 동작은 <b>이어서 하기 · 시작 · 문제 열기 · 답변 · 다시 설명</b> 다섯이다.
 * 답변에 문제·질문을 싣지 않는 것이 이 설계의 핵심이다 — 진행 위치는 서버 커서가 정본이고, 그래야
 * 새로고침·재접속이 별도 복구 API 없이 {@code GET /current} 하나로 해결된다.
 *
 * <p>다시 설명을 답변과 나눈 것은 성격이 다르기 때문이다. 답변은 AI 채점이라 몇 초가 걸리고 실패하면
 * 재전송을 요구하지만, 다시 설명은 동결된 문구를 꺼내는 즉답이고 몇 번을 눌러도 같다.
 */
@Tag(name = "Assessment", description = "교육생 이해도 확인 회차")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('TRAINEE')")
@Validated
@RestController
@RequestMapping(value = "/assessment-sessions", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AssessmentSessionController {

	/** 분산 추적 ID. AI로 그대로 넘어가 {@code ai_usage.trace_id}로 돌아온다. */
	private static final String TRACE_ID_HEADER = "X-Request-Id";

	private final AssessmentSessionService sessionService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(
			summary = "지금 이어서 할 세션 조회 | ⚠️ 사용 불가",
			description = """
					TR-03 진입과 **복귀**를 함께 처리한다. 진행 중인 세션이 있으면 그것을, 없으면 시작할 수 있는
					세션을 준다 — 새로고침·브라우저 종료 후 재접속이 이 경로 하나로 해결되므로 별도 복구 API가 없다.

					## 요청

					파라미터가 없다. 대상은 **액세스 토큰의 사용자**에서 도출한다(경로로 받지 않는다).

					## 응답

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `sessionId` | UUID | 이후 네 경로가 모두 이 값을 쓴다 |
					| `mode` | enum | `FIRST`(1차) · `REVIEW`(다시 보기). REVIEW는 힌트가 없고 판정에 반영되지 않는다 |
					| `status` | enum | `READY`(시작 전 안내) · `IN_PROGRESS`(진행 중) |
					| `currentProblemNo` | int? | 지금 서 있는 문제 번호(1~3). 시작 전이면 `null` |
					| `problemTotal` | int | 생성된 문제 수. 화면의 `문제 n/N`의 N |
					| `startedAt` | date-time? | 경과 시간 표시의 기산점. 시작 전이면 `null` |
					| `timeLimitAt` | date-time? | 정책 시간 상한. 상한이 없으면 `null` |
					| `reviewDueAt` | date-time? | 다시 보기 마감. `mode=FIRST`이면 `null` |

					⚠️ `problemTotal`은 **3이 아닐 수 있다.** `NOT_GENERATED` 문제에는 단계를 만들지 않으므로
					화면의 `n/3` 하드코딩은 틀린다.

					진행 중인 세션을 다시 보기보다 먼저 고른다. 둘 다 없으면 **`204 No Content`**이며 화면은
					`진행 중인 회차 없음`으로 그린다.""")
	@GetMapping("/current")
	public ResponseEntity<SessionResponse> findCurrent() {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		return sessionService.findCurrent(userId)
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@Operation(
			summary = "세션 시작(인트로 동의) | ⚠️ 사용 불가",
			description = """
					시작 전 안내에서 `전체화면으로 시작하기`를 눌렀을 때 부른다. `READY → IN_PROGRESS`로 옮기고
					**인트로 고지 동의를 함께 남긴다** — 무효 응시 검토에서 "그때 무엇을 고지받았나"를 이 기록으로
					되짚기 때문에 선택이 아니다.

					## 요청 (경로 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `sessionId` | 필수 | UUID | `GET /current`가 준 값 |

					본문은 없다.

					## 응답

					`GET /current`와 **같은 구조**다. `status`가 `IN_PROGRESS`로 바뀌고 `startedAt`·`timeLimitAt`이
					채워진다. 커서는 첫 문제의 L1에 선다.

					**이미 진행 중이면 그대로 돌려준다** — 새로고침 후 다시 눌러도 커서가 처음으로 돌아가지 않는다.

					## 오류

					| 코드 | 상태 | 언제 |
					|---|---|---|
					| `SESSION_NOT_ACCESSIBLE` | 404 | 없거나 남의 세션 |
					| `SESSION_ALREADY_ENDED` | 409 | 이미 끝난 세션 |""")
	@PostMapping("/{sessionId}/start")
	public ResponseEntity<SessionResponse> start(@PathVariable UUID sessionId) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		return ResponseEntity.ok(sessionService.start(userId, sessionId));
	}

	@Operation(
			summary = "문제 하나의 코드·질문·문답 조회 | ⚠️ 사용 불가",
			description = """
					왼쪽 코드 패널과 오른쪽 대화가 이 한 번의 조회로 채워진다.

					## 요청 (경로 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `sessionId` | 필수 | UUID | 세션 식별자 |
					| `problemNo` | 필수 | int | 문제 번호 `1`~`3` |

					## 응답

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `problemNo` | int | 문제 번호 |
					| `problemTotal` | int | 생성된 문제 수. 화면의 `문제 n/N` |
					| `title` | string | 문제 제목. 검증하는 교안 개념 이름이다 |
					| `code` | object | 코드 패널. 구조는 아래 |
					| `turns[]` | array | 이 문제에서 지금까지 확정된 문답. 화면은 위에서 아래로 쌓는다 |
					| `current` | object? | 지금 물어보는 질문. 문제가 끝났으면 `null` |

					**code**

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `path` | string | 파일 경로 |
					| `language` | string | `PYTHON` · `JAVA` … 모르는 확장자는 `UNKNOWN` |
					| `snippet` | string | **문제를 낸 파일 전체.** 자를 위치는 화면이 정한다 |
					| `lineStart` · `lineEnd` | int | 강조할 구간(파일 기준 절대 줄 번호) |
					| `references[]` | array | `{ type, path, lineStart, lineEnd, axisCode }`. 호출부·관련 문맥. 화면은 접어 두고 필요할 때 편다 |

					**turns[] 각 항목**

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `sequenceNo` | int | 질문 순번. 화면의 `◆ 질문 2` |
					| `questionText` | string | 질문 원문 |
					| `hintText` | string? | 이 턴 직전에 보여준 힌트. 첫 시도면 `null` |
					| `answerText` | string | 학생 답변 원문 |
					| `answeredAt` | date-time | 제출 시각 |
					| `highlight` | object | `{ path, lineStart, lineEnd }`. 질문마다 옮겨간다 |

					**current**

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `sequenceNo` | int | 질문 순번 |
					| `questionText` | string | 질문 원문 |
					| `shownHints[]` | array | 이미 연 힌트 문구. 없으면 빈 배열 |
					| `hintsUsed` | int | 지금까지 쓴 힌트 수(0~2) |
					| `hintsLeft` | int | 남은 힌트 수. 다시 보기는 항상 `0` |
					| `highlight` | object | 강조 구간 |
					| `lastTurnOfSession` | boolean | `true`면 버튼이 `답변 제출하고 마치기`로 바뀐다 |

					⚠️ **점수·통과 여부는 응답에 없다**(정의서 §7 "세션 중에는 아무 판정도 안 보여준다").
					화면이 안 그려도 응답에 있으면 개발자 도구로 보이고, 그 순간 학생은 다음 답을 점수에 맞춰 쓴다.

					## 오류

					| 코드 | 상태 | 언제 |
					|---|---|---|
					| `PROBLEM_NOT_FOUND` | 404 | 그 번호의 문제가 이 세션에 없다 |
					| `PROBLEM_ALREADY_CLOSED` | 409 | **지금 문제가 아니다.** 진행 중에는 커서가 선 문제만 열린다 |

					`PROBLEM_ALREADY_CLOSED`는 정의서 §3 때문이다 — 끝난 문제를 다시 열면 지금 문제와 무관한 데
					시간을 쓰고 "아까 그거 틀린 것 같은데"만 남는다. 아직 시작하지 않은 뒤 문제도 같은 이유로 막는다.
					**세션이 끝난 뒤에는 전부 열린다.**""")
	@GetMapping("/{sessionId}/problems/{problemNo}")
	public ResponseEntity<ProblemActivityResponse> findProblem(
			@PathVariable UUID sessionId,
			@Parameter(description = "문제 번호(1~3)") @PathVariable @Min(1) @Max(3) int problemNo) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		return ResponseEntity.ok(sessionService.findProblem(userId, sessionId, problemNo));
	}

	@Operation(
			summary = "답변 제출 → 채점 → 다음 질문 | ⚠️ 사용 불가",
			description = """
					답변을 AI에 보내 채점하고 다음 자리를 정한다.

					## 요청 (본문)

					| 필드 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `answerText` | 필수 | string | 학생이 쓴 답변 원문 |

					⚠️ **어느 문제의 어느 질문인지는 싣지 않는다.** 진행 위치는 서버 커서가 정본이다 —
					클라이언트가 지목하게 두면 계단을 건너뛰거나 이미 닫힌 문제에 답을 붙이는 요청이 만들어지고,
					서버는 그것이 진짜 화면 상태인지 알 방법이 없다.

					길이 하한도 없다. 정의서 §6 — 짧은 답변은 알리되 막지 않는다("강제하면 의미 없는 글자를
					채운다"). 15자 미만 안내는 화면이 한다.

					## 요청 (헤더)

					| 헤더 | 필수 | 설명 |
					|---|---|---|
					| `X-Request-Id` | 선택 | 분산 추적 ID. AI로 그대로 넘어가 `ai_usage.trace_id`로 돌아온다 |

					## 응답

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `outcome` | enum | `RETRY_WITH_HINT` · `NEXT_TURN` · `NEXT_PROBLEM` · `PROBLEM_CLOSED` · `SESSION_ENDED` |
					| `nextProblemNo` | int? | 다음에 설 문제 번호. 세션이 끝났으면 `null` |
					| `next` | object? | 다음 질문. 세션이 끝났으면 `null` |
					| `hint` | object? | 3점 미만이라 **자동으로 열린** 힌트. 아니면 `null` |

					**hint** — `{ hintText, hintsUsed, hintsLeft }`

					⚠️ **`hint`가 오면 같은 질문에 다시 답하는 것이다.** 화면은 새 질문 말풍선을 쌓지 말고
					힌트를 덧붙인 뒤 같은 자리에서 답을 다시 받는다(`outcome=RETRY_WITH_HINT`).
					`POST /hints`와 **같은 UPDATE로 표시 시각까지 남긴 뒤** 내려오므로, 이 응답을 받고
					`POST /hints`를 따로 부르면 힌트를 두 개 쓰게 된다.

					`hint`가 `null`인 경우는 넷 — 통과했다 · 힌트를 다 썼다 · AI가 이 질문을 닫았다 ·
					다시 보기다. 앞의 셋은 `outcome`으로 갈린다.

					💡 **이것이 점수를 알려주지 않으면서 미달을 전하는 유일한 신호다.** 점수·통과 여부는
					응답에 없다(정의서 §7).

					## 한 질문의 수명

					```
					답변 ─3점 이상→ 통과. AI가 정한 다음 자리로 (NEXT_TURN · NEXT_PROBLEM · SESSION_ENDED)
					     └3점 미만→ 힌트 자동 공개 + 같은 질문 재도전 (RETRY_WITH_HINT)  ← 최대 2회
					                └ 힌트 2개 다 쓰고도 미달 → **다음 문제로** (PROBLEM_CLOSED)
					```

					⚠️ **마지막 줄은 축과 무관하다.** `L1`에서 힌트 2개를 쓰고 미달이어도 `L2`를 묻지 않고
					곧바로 다음 문제로 넘어간다 — 두 번 설명하고도 닿지 않았으면 같은 코드에 더 물어도
					얻을 것이 없다는 학습 정책이다. **이 판정만 백엔드가 AI 커서를 덮어쓴다**(나머지
					진행은 전부 AI가 정한다). 접힌 문제의 남은 축은 `NOT_REACHED`로 닫힌다.

					다음 문제가 없으면 `SESSION_ENDED`다.

					**next**

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `problemId` | UUID | 다음 질문이 속한 문제 |
					| `axisCode` | enum | 이 질문이 서 있는 축. `L1` · `L2` · `L3` · `L4` |
					| `sequenceNo` | int | 질문 순번 |
					| `questionText` | string | 질문 원문 |
					| `hintsUsed` | int | 새 자리의 힌트 사용 수(보통 `0`) |
					| `highlight` | object? | `{ path, lineStart, lineEnd }`. 이 질문이 가리키는 코드 구간 |

					💡 **질문마다 강조 구간이 다르다.** 축이 올라가면(`L1`→`L2`) 같은 파일의 다른 블록을
					가리킨다 — 예: 질문 1은 `graph.py:5–8`, 질문 2는 `graph.py:39–41`. 화면은
					`next.highlight`로 왼쪽 패널의 강조만 옮기면 된다. 코드 원문(`code.snippet`)은 파일
					전체라 `NEXT_TURN`에서는 다시 받을 필요가 없다.

					`highlight`가 `null`인 경우는 AI가 준 `problemId`를 우리 행과 대조하지 못한 때뿐이다.
					그때는 `GET /{sessionId}/problems/{problemNo}`를 다시 불러 채운다.

					`outcome`이 화면 전환을 정한다 — `NEXT_PROBLEM`은 `다음 문제로`, `PROBLEM_CLOSED`는
					`이 문제는 여기까지 볼게요`, `SESSION_ENDED`는 종료 화면이다.
					앞의 둘은 **문제가 바뀌므로** `GET /{sessionId}/problems/{nextProblemNo}`로 새 코드 패널을
					받아야 한다. `NEXT_TURN`은 재조회 없이 `next`만으로 그린다.

					⚠️ **응답에 점수가 없다**(정의서 §7). 저장은 이미 끝났고 여기서는 흐름만 알려준다.

					## 오류

					| 코드 | 상태 | 언제 |
					|---|---|---|
					| `ANSWER_TEXT_REQUIRED` | 400 | 본문이 비었다 |
					| `SESSION_NOT_STARTED` | 409 | `POST /start`를 아직 부르지 않았다 |
					| `SESSION_TIMEOUT` | 409 | 시간 상한 초과. 답한 데까지 저장하고 세션을 닫는다 |
					| `ANSWER_ALREADY_SUBMITTED` | 409 | 같은 자리에 이미 제출됐다(낙관적 잠금). 다시 불러오면 된다 |
					| `GRADING_FAILED` | 503 | AI 채점 실패. **같은 답을 그대로 다시 제출하면 된다** |

					⚠️ AI 채점에 **4.5~7.7초**가 걸린다. 클라이언트 타임아웃을 짧게 잡지 말 것.
					재전송이 안전한 이유는 서버가 자리마다 고정된 멱등키를 만들어 보내기 때문이다 —
					같은 자리 재시도는 AI가 처음 응답을 그대로 돌려주므로 LLM 비용이 두 번 나가지 않는다.""")
	@PostMapping(value = "/{sessionId}/answers", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<AnswerSubmitResponse> submitAnswer(
			@PathVariable UUID sessionId,
			@Valid @RequestBody AnswerSubmitRequest request,
			@RequestHeader(value = TRACE_ID_HEADER, required = false) String traceId) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		return ResponseEntity.ok(sessionService.submitAnswer(userId, sessionId, request, traceId));
	}

	@Operation(
			summary = "다시 설명(힌트) 요청 | ⚠️ 사용 불가",
			description = """
					`다시 설명해 주세요`를 눌렀을 때 부른다. **AI를 부르지 않는다** — 힌트 문구는 코드 분석 시점에
					이미 동결돼 DB에 있고 세션은 꺼내 보여줄 뿐이다("힌트는 재진술만 — 질문을 다르게 말할 뿐
					코드 위치·선택지·답의 방향을 주지 않는다"). 즉답이다.

					💡 **힌트가 열리는 경로는 둘이다.** 답변이 3점 미만이면 `POST /answers`의 응답에
					`hint`로 **자동으로** 열려 내려오고, 학생이 원할 때는 이 경로로 **직접** 연다.
					둘은 같은 횟수(질문당 2회)를 나눠 쓰며 같은 UPDATE를 탄다.

					답변란이 비어 있어도 부를 수 있다 — 질문을 이해하지 못했을 때 미리 보는 용도다.
					반대로 **끝난 질문에는 열리지 않는다**(통과했거나, 마지막 힌트까지 쓰고 미달이라
					`NOT_PASSED`로 닫혔다).

					⚠️ `POST /answers`가 `hint`를 함께 준 뒤에 이 경로를 또 부르면 **두 번째 힌트가 열린다.**
					자동으로 받은 힌트는 이미 소진된 것이므로 화면은 그것을 그리기만 하고 다시 부르지 않는다.

					## 요청 (경로 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `sessionId` | 필수 | UUID | 세션 식별자 |

					본문은 없다. 어느 질문의 힌트인지는 서버 커서가 정한다.

					## 응답

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `problemId` | UUID | 이 힌트가 속한 문제 |
					| `axisCode` | enum | `L1` · `L2` · `L3` · `L4` |
					| `hintText` | string | 힌트 문구. 분석 시점에 동결된 것을 그대로 준다 |
					| `hintsUsed` | int | 지금까지 쓴 힌트 수(1~2) |
					| `hintsLeft` | int | 남은 횟수. `0`이면 화면은 버튼을 문구로 바꾼다 |

					⚠️ **써도 점수가 깎이지 않는다.** 남은 횟수를 내려보내는 것은 화면이 `2번 남음`을 그리기
					위해서이지 불이익을 알리기 위해서가 아니다.

					## 오류

					| 코드 | 상태 | 언제 |
					|---|---|---|
					| `HINT_EXHAUSTED` | 409 | 단계당 2회를 다 썼다 |
					| `HINT_NOT_AVAILABLE` | 409 | 다시 보기이거나, 이미 답을 제출한 질문이다 |
					| `SESSION_NOT_STARTED` | 409 | `POST /start`를 아직 부르지 않았다 |

					다시 보기에서 막는 근거는 정의서 §6+다 — "이번에는 다시 설명해 드리지 않아요. 지난번과 같은
					질문이라 이미 한 번 들었어요."

					AI를 안 부르는데도 서버를 타는 이유는 **표시 시각을 남기기 위해서다.** 그 기록이 없으면
					힌트를 열어 둔 채 새로고침했을 때 사용 횟수가 0으로 되돌아가 학생이 힌트를 세 번, 네 번 쓴다.""")
	@PostMapping("/{sessionId}/hints")
	public ResponseEntity<HintResponse> openHint(@PathVariable UUID sessionId) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		return ResponseEntity.ok(sessionService.openHint(userId, sessionId));
	}

	@Operation(
			summary = "응시 중 관찰 신호 기록 | ⚠️ 사용 불가",
			description = """
					창 이탈·연결 끊김·첫 타이핑 지연을 남긴다. **AI를 부르지 않고 진행 상태도 바꾸지 않는다** —
					오직 기록이며 응답 본문이 없다(`204`).

					## 요청

					| 파라미터 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `sessionId` | 필수 | UUID | 경로 파라미터 |

					**본문** — 셋 다 선택이되 **최소 하나**는 있어야 한다. 여러 개를 함께 보내도 된다.

					| 필드 | 타입 | 범위 | 설명 |
					|---|---|---|---|
					| `awaySeconds` | int? | `0`~`86400` | 창을 떠나 있던 시간(초) |
					| `disconnectedSeconds` | int? | `0`~`86400` | 연결이 끊겼던 시간(초) |
					| `firstKeystrokeDelayMs` | int? | `0`~`86400000` | 질문이 보인 뒤 첫 글자까지(ms) |

					어느 문제의 어느 질문에 붙는지는 **싣지 않는다** — 답변 제출과 같은 이유로 진행 위치는
					서버 커서가 정본이다.

					## 언제 부르나

					| 화면 이벤트 | 보낼 값 |
					|---|---|
					| `visibilitychange`로 돌아옴 · `focus` | `awaySeconds` — **복귀 시점에 한 번만** |
					| 소켓/요청 재연결 성공 | `disconnectedSeconds` — 재연결 시점에 한 번만 |
					| 답변 입력창의 첫 키 입력 | `firstKeystrokeDelayMs` |

					⚠️ `awaySeconds`·`disconnectedSeconds`는 **보낼 때마다 횟수가 1씩 올라간다.** 이탈 중에
					주기적으로 보내면 한 번 나간 것이 열 번으로 기록되어 무효 응시 판정
					(`EXCESSIVE_WINDOW_LEAVE`·`EXCESSIVE_CONNECTION_LOSS`)이 틀린다. 반면
					`firstKeystrokeDelayMs`는 슬롯당 첫 값만 남으므로 중복 전송이 안전하다.

					## 응답

					`204 No Content`. 본문이 없다.

					## 오류

					| 코드 | 상태 | 언제 |
					|---|---|---|
					| `ACTIVITY_SIGNAL_REQUIRED` | 400 | 세 값이 모두 비었다 |
					| `SESSION_NOT_ACCESSIBLE` | 404 | 없거나 남의 세션 |
					| `SESSION_NOT_STARTED` | 409 | `POST /start`를 아직 부르지 않았다 |
					| `SESSION_TIMEOUT` | 409 | 시간 상한 초과. 그 자리에서 세션을 닫는다 |
					| `SESSION_ALREADY_ENDED` | 409 | 이미 끝난 세션 |

					💡 **끝난 세션의 신호는 버린다.** 세션을 닫은 뒤 도착한 복귀 비콘까지 받아 주면 종료
					시각 이후의 이탈이 합계에 섞인다. 화면은 `409`를 무시하면 된다 — 재전송할 값이 아니다.

					이 경로가 없으면 `window_leave_count`·`connection_loss_count`·`*_away_count`·
					`*_first_keystroke_delay_ms`가 전부 초기값으로 남고, 무효 응시 판정과 매니저 브리프의
					"어느 답변이 의심스러운가"가 빈 값으로 돌아간다.""")
	@PostMapping(value = "/{sessionId}/activity", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Void> recordActivity(
			@PathVariable UUID sessionId,
			@Valid @RequestBody SessionActivityRequest request) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		sessionService.recordActivity(userId, sessionId, request);
		return ResponseEntity.noContent().build();
	}
}
