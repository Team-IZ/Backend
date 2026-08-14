package com.bigproject.backend.domain.intervention.presentation;

import com.bigproject.backend.domain.intervention.application.InterviewBriefService;
import com.bigproject.backend.domain.intervention.presentation.dto.InterviewBriefResponse;
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
				currentUserResolver.resolveCurrentMemberId(), extractOrganizationId(authentication), caseId);

		return ResponseEntity.ok(InterviewBriefResponse.from(view));
	}

	private UUID extractOrganizationId(Authentication authentication) {
		Object details = authentication.getDetails();
		if (!(details instanceof UUID organizationId)) {
			throw new IllegalStateException("인증 정보에서 organizationId를 확인할 수 없습니다.");
		}
		return organizationId;
	}
}
