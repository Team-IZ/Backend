package com.bigproject.backend.domain.intervention.presentation;

import com.bigproject.backend.domain.intervention.application.InterviewBriefService;
import com.bigproject.backend.domain.intervention.presentation.dto.InterviewBriefResponse;
import com.bigproject.backend.domain.intervention.presentation.dto.SaveInterviewBriefRequest;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * MG-04 면담 브리프.
 *
 * <p>생성({@code POST})과 조회({@code GET})를 나눈다 — 생성은 쓰기가 세 겹으로 일어나고
 * AI를 부르는 동작이라 {@code GET}에 부수효과를 두지 않는다.
 */
@Tag(name = "Intervention", description = "면담")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping(value = "/interviews/{caseId}/brief", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('MANAGER')")
@RequiredArgsConstructor
public class InterviewBriefController {

	private final InterviewBriefService interviewBriefService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(operationId = "findInterviewBrief", summary = "면담 브리프 조회", description = """
			저장된 브리프를 읽습니다. **AI를 부르지 않아 즉시 반환됩니다.**

			### 생성은 이 경로가 하지 않습니다

			브리프는 `[브리프 생성]` 클릭 시 `POST`로 **1회만** 만들고, 그 뒤로는 이 `GET`이
			저장본을 돌려줍니다(정의서 §5 "그때 브리프를 만든다 — 미리 만들어 두지 않는다").
			아직 만들지 않은 케이스는 **404 `INTERVIEW_BRIEF_NOT_CREATED`** 입니다 —
			화면은 그 응답을 받으면 `[브리프 생성]` 버튼을 그립니다.

			### 질문은 전부 반환합니다

			`interview_brief_item.is_selected`로 거르지 않습니다. 화면에 질문을 고르는 UI가 없어
			(정의서 §7) 전 항목을 그대로 그립니다.

			### 아직 비어 있는 필드

			| 필드 | 사유 |
			|---|---|
			| `concepts` | 교안 위치·반 문제 판정이 DB 회신 대기. 빈 배열 |
			| `voidEvidence` | "질문 문장 그대로 복사" 판정 규칙이 미확정. null |
			""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "조회 성공"),
			@ApiResponse(responseCode = "404", description = """
					INTERVIEW_CASE_NOT_FOUND 담당 범위에서 찾을 수 없음
					· INTERVIEW_BRIEF_NOT_CREATED 아직 생성되지 않은 브리프"""),
			@ApiResponse(responseCode = "403", description = "매니저 권한이 없음")
	})
	@GetMapping
	public ResponseEntity<InterviewBriefResponse> findBrief(
			@Parameter(description = "면담 케이스 ID", required = true)
			@PathVariable UUID caseId,
			Authentication authentication
	) {
		InterviewBriefService.BriefView view = interviewBriefService.findBrief(
				currentUserResolver.resolveCurrentMemberId(), ActorContext.organizationId(authentication), caseId);

		return ResponseEntity.ok(InterviewBriefResponse.from(view));
	}

	@Operation(operationId = "createInterviewBrief", summary = "면담 브리프 생성 (AI)", description = """
			**AI를 호출해 여는 말과 질문 체크리스트를 만듭니다. 수 초~수십 초 걸립니다.**

			화면은 이 응답을 기다리는 동안 로딩 상태를 유지해야 합니다 — AI가 동기 계약이라
			(202+폴링이 아니라 200) 이 응답이 곧 결과입니다.

			### 브리프당 한 번만 만듭니다

			이미 완성된 브리프가 있으면 **재생성하지 않고 그대로 돌려줍니다.**
			매니저가 열 때마다 여는 말이 달라지면 안 되고, LLM 비용도 열람 횟수만큼 나가서는
			안 됩니다. 의도적인 재생성은 별도 경로(`/regenerate`)입니다.

			### 무효 확인이 먼저입니다

			위험 유형이 `INVALID`인데 아직 판정하지 않았으면 **409**입니다.
			판정 전에는 `briefType`(STANDARD/INVALID_ATTEMPT)을 정할 수 없어 여는 말과 질문이
			통째로 어긋납니다.

			### 실패해도 브리프 행은 남습니다

			태운 토큰을 원장에 남겨야 하고 중복 호출 방지 장치가 그 행을 씁니다.
			그 상태에서 목록의 `briefState`는 `FAILED`가 되고 화면은 `[다시 생성]`을 그립니다.
			""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "생성 완료(또는 이미 있던 브리프)"),
			@ApiResponse(responseCode = "404", description = "INTERVIEW_CASE_NOT_FOUND 담당 범위에서 찾을 수 없음"),
			@ApiResponse(responseCode = "409", description = "VALIDITY_REVIEW_REQUIRED 무효 확인을 먼저 처리해야 함"),
			@ApiResponse(responseCode = "503", description = """
					BRIEF_GENERATION_FAILED 다시 불러도 같은 실패(계약 위반·멱등 충돌)
					· BRIEF_GENERATION_FAILED_RETRYABLE 재시도 가치가 있는 실패(타임아웃 등)""")
	})
	@PostMapping
	public ResponseEntity<InterviewBriefResponse> createBrief(
			@Parameter(description = "면담 케이스 ID", required = true)
			@PathVariable UUID caseId,
			@RequestHeader(value = "X-Trace-Id", required = false) String traceId,
			Authentication authentication
	) {
		InterviewBriefService.BriefView view = interviewBriefService.createBrief(
				currentUserResolver.resolveCurrentMemberId(),
				ActorContext.organizationId(authentication), caseId, traceId);

		return ResponseEntity.ok(InterviewBriefResponse.from(view));
	}

	@Operation(operationId = "saveInterviewBrief", summary = "브리프 저장하고 면담 종결", description = """
			**저장은 항상 종결입니다**(정의서 §5). 별도의 "면담 시작" 단계가 없습니다 —
			면담하는 30분 동안 매니저는 화면을 안 보기 때문입니다.

			### 한 트랜잭션에서 여섯 가지를 합니다

			| | |
			|---|---|
			| ① | 원인 분류 **전체 교체**(기존 삭제 후 재삽입) |
			| ② | 매니저 기록 **덧붙이기**(기존 행은 고치지 않음) |
			| ③ | 브리프 확정 — 질문을 전부 `is_selected=TRUE`로 |
			| ④ | 잠금 재검증 — 선택 항목 1건 이상 |
			| ⑤ | 면담 `PENDING → COMPLETED` **직행** |
			| ⑥ | 상태 이력 1행 |

			`started_at`은 브리프를 연 시각을 알 수 없어 `completed_at`과 같은 값으로 기록합니다.

			### 종결 후 다시 저장하면 브리프는 그대로입니다

			수정 대상은 **원인과 매니저 기록뿐**이고 여는 말·질문은 종결 시점 그대로 고정됩니다.
			이 경우 ③~⑥을 건너뜁니다.

			### 빈 값도 저장됩니다

			원인 0건, 상세 사유 없음, 추후 계획 없음 모두 허용합니다 — 면담이 항상 원인을
			찾아내지는 않습니다.
			""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "저장·종결 완료. 갱신된 브리프를 반환"),
			@ApiResponse(responseCode = "404", description = """
					INTERVIEW_CASE_NOT_FOUND 담당 범위에서 찾을 수 없음
					· INTERVIEW_BRIEF_NOT_CREATED 브리프가 없거나 생성 실패 상태"""),
			@ApiResponse(responseCode = "409", description = """
					BRIEF_HAS_NO_SELECTED_ITEM 질문이 없는 브리프
					· INTERVIEW_ROW_VERSION_CONFLICT 그 사이 상태가 바뀜""")
	})
	@PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<InterviewBriefResponse> saveBrief(
			@Parameter(description = "면담 케이스 ID", required = true)
			@PathVariable UUID caseId,
			@RequestBody SaveInterviewBriefRequest request,
			Authentication authentication
	) {
		InterviewBriefService.BriefView view = interviewBriefService.saveAndComplete(
				currentUserResolver.resolveCurrentMemberId(),
				ActorContext.organizationId(authentication), caseId,
				request.causes(), request.why(), request.nextAction());

		return ResponseEntity.ok(InterviewBriefResponse.from(view));
	}

	@Operation(operationId = "regenerateInterviewBrief", summary = "면담 브리프 재생성 (AI)", description = """
			여는 말과 질문을 **다시 만듭니다. LLM 비용이 또 나가고 되돌릴 수 없습니다** —
			화면은 확인 다이얼로그를 띄운 뒤 호출해야 합니다.

			기존 브리프는 `SUPERSEDED`로 밀려나고 `version_no`가 올라갑니다.
			버전이 바뀌므로 멱등키(`{briefId}:{versionNo}`)도 자동으로 달라져
			`ai_usage.idempotency_key` 전역 UNIQUE 충돌을 피합니다.

			### 생성 실패 후 재시도와 다릅니다

            | | 멱등키 | 동작 |
            |---|---|---|
            | **재시도** — `POST .../brief` 다시 | **같은 키** | AI가 캐시된 결과를 준다 |
            | **재생성** — 이 경로 | **새 키** | LLM을 다시 태운다 |

			### 종결된 면담은 재생성할 수 없습니다

			**409**입니다. 지난 면담에서 실제로 무엇을 물었는지가 다음 회차 브리프의
			`askedQuestions`로 이어지므로 사후에 바꾸면 그 기록이 사실과 달라집니다.
			원인·기록은 `PUT`으로 여전히 고칠 수 있습니다.
			""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "재생성 완료"),
			@ApiResponse(responseCode = "404", description = """
					INTERVIEW_CASE_NOT_FOUND 담당 범위에서 찾을 수 없음
					· INTERVIEW_BRIEF_NOT_CREATED 아직 만든 적 없는 브리프"""),
			@ApiResponse(responseCode = "409", description = "BRIEF_NOT_EDITABLE 종결된 면담"),
			@ApiResponse(responseCode = "503", description = "BRIEF_GENERATION_FAILED(_RETRYABLE) 생성 실패")
	})
	@PostMapping("/regenerate")
	public ResponseEntity<InterviewBriefResponse> regenerateBrief(
			@Parameter(description = "면담 케이스 ID", required = true)
			@PathVariable UUID caseId,
			@RequestHeader(value = "X-Trace-Id", required = false) String traceId,
			Authentication authentication
	) {
		InterviewBriefService.BriefView view = interviewBriefService.regenerateBrief(
				currentUserResolver.resolveCurrentMemberId(),
				ActorContext.organizationId(authentication), caseId, traceId);

		return ResponseEntity.ok(InterviewBriefResponse.from(view));
	}
}
