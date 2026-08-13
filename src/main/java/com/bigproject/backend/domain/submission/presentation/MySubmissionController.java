package com.bigproject.backend.domain.submission.presentation;

import com.bigproject.backend.domain.submission.application.MySubmissionService;
import com.bigproject.backend.domain.submission.presentation.dto.MySubmissionResponse;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * TR-02 제출 현황 조회.
 *
 * <p>{@link SubmissionController}와 나눈 이유는 경로 뿌리가 다르기 때문이다 — 그쪽은
 * {@code /submissions}에 걸려 있고 이 조회는 프로젝트 스코프({@code /projects/{projectId}/…})다.
 * 화면이 홈에서 {@code projectId}를 들고 넘어오지 {@code submissionId}를 알지 못한다.
 */
@Tag(name = "Submission", description = "교육생 코드 제출과 코드 분석 상태 조회 API")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('TRAINEE')")
@RestController
@RequiredArgsConstructor
public class MySubmissionController {

	private final MySubmissionService mySubmissionService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(
			operationId = "findMySubmission",
			summary = "내 팀의 제출 현황 조회 | ✅ 사용 가능",
			description = """
					TR-02 `제출` 화면 전체를 이 응답 하나로 그린다. 제출 폼·분석 진행·재제출 가능 여부가
					모두 `status` 하나에서 갈린다.

					## 🔴 상태를 서버가 하나로 판정한다

					화면이 *제출했나 · 분석 끝났나 · 세션 시작했나 · 마감 지났나* 를 조합해 상태를 유추하지
					않게 한 것이 이 API의 요점이다. 조합하면 같은 판정 규칙이 화면과 서버 양쪽에 생기고,
					규칙이 바뀔 때 반드시 한쪽만 바뀐다.

					| status | 뜻 | 함께 오는 것 |
					|---|---|---|
					| `DRAFT` | 아직 제출 안 함 → 폼 | `submissionDueAt` |
					| `ANALYZING` | 제출됨·분석 중 | `submittedAt` · `submissionId` |
					| `READY` | 분석 완료 → **아직 다시 제출할 수 있다** | `analyzedAt` · `verifyClosesAt` · `content` |
					| `LOCKED` | **세션을 시작해서 잠김** — 다시 제출 불가 | 위와 같음 |
					| `ANALYSIS_FAILED` | 분석 실패 → 폼을 이전 값으로 되채워 다시 제출 | `failureReason` · `failureCode` · `content` |
					| `SUBMISSION_CLOSED` | 미제출인 채로 마감이 지났다 | `submissionDueAt` |

					**판정 순서가 규칙이다** — 위에서부터가 아니라 아래 순서로 먼저 맞는 것 하나로 정한다.

					1. 제출이 없다 → 마감 전이면 `DRAFT`, 지났으면 `SUBMISSION_CLOSED`
					2. 제출 접수가 깨졌거나(`FETCH_FAILED`·`INVALID`) 분석 job이 `FAILED` → `ANALYSIS_FAILED`
					3. 분석이 아직 `SUCCEEDED`·`PARTIAL`이 아니다 → `ANALYZING`
					4. 세션을 시작했다 → `LOCKED`, 아니면 `READY`

					⚠️ 마감을 가장 먼저 보지 않는다. 마감이 지나도 이미 분석까지 끝났다면 화면이 그려야 할
					것은 "마감 지남"이 아니라 응시 안내다. `SUBMISSION_CLOSED`는 **미제출인 채로** 마감이
					지났다는 뜻이다.

					### `LOCKED`가 중요하다

					질문이 **제출된 코드로** 만들어지므로 세션을 시작한 뒤 코드가 바뀌면 질문과 답이 어긋난다.
					그래서 `READY`와 `LOCKED`를 서버가 가른다 — 화면은 두 상태에 다른 문구를 띄우면 된다.

					**잠금은 개인 단위다.** 제출·분석은 팀 단위지만 세션은 사람마다 따로 열리므로, 팀원 한
					명이 세션을 시작했다고 나머지가 잠기지 않는다. 같은 제출을 두고도 사람마다 `READY`와
					`LOCKED`가 갈릴 수 있다.

					## content — 두 수단이 같은 카드를 채운다

					| 필드 | 타입 | GitHub | ZIP | 설명 |
					|---|---|---|---|---|
					| `repoUrl` | string? | ✅ | — | 교육생이 입력한 **원문** 주소. 정규화 전이라 폼에 그대로 되채운다 |
					| `branch` | string? | ✅ | — | 실제로 분석된 브랜치. 분석 전에는 적어 낸 값, 비워 냈으면 기본 브랜치 |
					| `fileName` | string? | — | ✅ | 올린 파일 이름. 예: `team3-miniproject.zip` |
					| `fileSize` | int64? | — | ✅ | 올린 파일 크기(바이트) |
					| `lastCommit` | object? | ✅ | ✅ | `{sha, message, at}` — **분석 성공 후에만** |

					**필드는 수단별로 배타적이다.** GitHub이면 저장소·브랜치가, ZIP이면 파일 이름·크기가
					채워지고 반대쪽은 키가 빠진다. 화면은 `repoUrl`이 있으면 저장소 줄을, `fileName`이
					있으면 파일 줄을 그리면 된다.

					🔴 **ZIP의 커밋 정보는 분석 결과에서 온다.** `ck_submission_method_2`가 ZIP 분기의
					`source_commit_*`를 NULL로 강제하고 git log를 읽는 주체가 AI라, 제출 직후에는
					`lastCommit`이 없다가 **분석이 끝나면 나타난다.** GitHub 쪽도 같은 시점에 채워진다.

					⚠️ **미제출이면 `content` 키 자체가 빠진다.** ZIP인데 아티팩트 행이 아직 없는
					접수 도중에도 마찬가지다 — 빈 객체를 보내면 화면이 "냈는데 내용이 비었다"로 읽는다.

					## 상태별로 쓰지 않는 필드는 키가 빠진다

					null을 실어 보내지 않는다 — `ANALYZING`인데 `verifyClosesAt`이 있으면 무슨 뜻인지를
					화면이 매번 판단하게 되기 때문이다.

					## 회차를 고르는 규칙

					`projectId`로 부르면 서버가 그 프로젝트의 **최신 회차**(`round_no DESC`) 하나를 고른다.
					정의서가 MINI_PROJECT에 "활성 회차 정확히 1건"을 요구하므로 지금은 사실상 1건이다.

					## 오류

					| 코드 | 상태 | 언제 |
					|---|---|---|
					| `SUBMISSION_ROUND_NOT_ACCESSIBLE` | 404 | 프로젝트가 없거나, 회차가 없거나, 호출자가 그 프로젝트의 유효 팀 구성원이 아니다. 셋을 구분하지 않는다 |
					""")
	@GetMapping(value = "/projects/{projectId}/my-submission", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<MySubmissionResponse> findMySubmission(
			@Parameter(description = "프로젝트 식별자. 회차 ID가 아니다") @PathVariable UUID projectId
	) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		return ResponseEntity.ok(mySubmissionService.getMySubmission(projectId, userId));
	}
}
