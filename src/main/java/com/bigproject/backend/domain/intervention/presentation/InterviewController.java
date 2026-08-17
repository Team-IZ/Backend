package com.bigproject.backend.domain.intervention.presentation;

import com.bigproject.backend.domain.intervention.application.InterviewService;
import com.bigproject.backend.domain.intervention.presentation.dto.InterviewListResponse;
import com.bigproject.backend.domain.intervention.presentation.dto.InterviewRoundOptionResponse;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * MG-03 면담 목록.
 *
 * <p>{@code /api/v0} 프리픽스는 {@code ApiPathConfig}가 자동으로 붙이므로 여기 쓰지 않는다.
 */
@Tag(name = "Intervention", description = "면담")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping(value = "/interviews", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('MANAGER')")
@RequiredArgsConstructor
public class InterviewController {

	private final InterviewService interviewService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(operationId = "findInterviews", summary = "[면담 목록] 면담 목록 조회 | ✅ 사용 가능", description = """
			위험 판정이 켜진 교육생의 **작업 큐**입니다. 매니저가 고르는 목록이 아니라
			이해도 확인 결과가 만든 목록이라, 회차 결과가 나오기 전에는 **비어 있는 것이 정상**입니다.

			### 스코프

			담당 반만 보입니다. `manager_interview_list_view`가 `manager_assignment`
			(`status='ACTIVE' AND unassigned_at IS NULL`)로 이미 걸러 주므로 별도 조인을 두지 않습니다.

			### 정렬 — 클라이언트가 바꿀 수 없습니다

			"이미 급한 순으로 온다"(정의서 §3). 서버 고정 규칙입니다.

			| 순위 | 기준 |
			|---|---|
			| ① | **무효 응시는 상태·반과 무관하게 항상 최상단** (9-4 "못하는 것보다 안 하는 것이 더 급하다") |
			| ② | 상태 — 예정·제외 먼저, 종결 나중 |
			| ③ | 반 이름 오름차순 |
			| ④ | 이름 가나다순 |

			### counts · riskCounts는 필터와 무관합니다

			**회차 전체 기준**입니다. 필터 옵션 라벨에 개수를 싣기 때문에
			(`상태 · 종결 (4)`) 필터링된 결과로 세면 고를수록 숫자가 줄어드는 화면이 됩니다.

			### 페이지네이션이 없습니다

			담당 반 한 회차라 수십 명 규모이고, 화면도 페이저를 그리지 않습니다.
			조건에 맞는 케이스를 전부 반환합니다.
			""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "조회 성공. 대상이 없으면 items가 빈 배열"),
			@ApiResponse(responseCode = "403", description = "매니저 권한이 없음"),
			@ApiResponse(responseCode = "500", description = "ORGANIZATION_CONTEXT_MISSING 인증 정보에서 organizationId를 확인할 수 없음")
	})
	@GetMapping
	public ResponseEntity<InterviewListResponse> findInterviews(
			@Parameter(description = "조회할 회차 ID", required = true,
					example = "123e4567-e89b-12d3-a456-426614174000")
			@RequestParam UUID assessmentRoundId,

			@Parameter(description = "교육생 이름 부분 일치. 공백이면 무시한다", example = "김민준")
			@RequestParam(required = false) String search,

			@Parameter(description = "상태 필터 `PLANNED` / `DONE` / `EXCLUDED`. 없으면 전체", example = "PLANNED")
			@RequestParam(required = false) String status,

			@Parameter(description = "위험 유형 필터 `INVALID` / `LOW_PERSISTENT` / `DECLINE` / `OBSERVE`. 없으면 전체",
					example = "DECLINE")
			@RequestParam(required = false) String riskType,

			@Parameter(description = "반 필터. 없으면 담당 반 전체")
			@RequestParam(required = false) UUID classId,

			Authentication authentication
	) {
		InterviewService.InterviewListResult result = interviewService.findCases(
				new InterviewService.InterviewListCriteria(
						currentUserResolver.resolveCurrentMemberId(),
						ActorContext.organizationId(authentication),
						assessmentRoundId,
						search,
						status,
						riskType,
						classId));

		return ResponseEntity.ok(InterviewListResponse.from(result));
	}

	@Operation(operationId = "findInterviewRoundOptions", summary = "[면담 목록] 면담 회차 옵션 조회 | ✅ 사용 가능", description = """
			목록 화면의 **회차 드롭다운**을 채웁니다. 담당 기수의 회차를 프로젝트 순서대로 반환합니다.

			### `PLANNED` 회차도 포함합니다

			결과가 아직 없는 회차를 고르면 목록이 *"이 회차는 아직 결과가 없어요"* 를 그리는 것이
			정의된 동작입니다(정의서 §6). 목록에서 아예 빼면 그 상태를 보여줄 방법이 없습니다.
			삭제된 회차만 제외합니다.

			### label에 프로젝트명이 붙습니다

			`round_no`가 **프로젝트 안에서만 유일**하기 때문입니다. 안 붙이면 서로 다른 프로젝트의
			1차가 드롭다운에 똑같이 두 번 보입니다.

			### 히트맵(MG-02)도 이 조회를 씁니다 (32차 R10)

			이름은 면담이지만 내용은 **담당 기수의 회차 목록**이라, 회차를 골라야 그릴 수 있는
			화면이면 어디든 맞습니다. `projectId`를 함께 실어 **`(projectId, assessmentRoundId)`
			짝**을 이 한 번의 조회로 얻을 수 있습니다.

			종전에는 그 짝을 주는 조회가 `GET /cohorts/{id}/trainees`(교육생 명부)뿐이라, 히트맵이
			**격자와 무관한 명부를 먼저 받아야** 했습니다. 그쪽은 행마다 지표를 붙이는 무거운
			조회라 `size=1`로 줄여도 시간이 줄지 않습니다 — 그것이 히트맵 첫 진입 시간이
			되고 있었습니다.

			별도 엔드포인트를 새로 열지 않은 이유는 **같은 질의**이기 때문입니다. 하나 더 만들면
			담당 반 스코프 규칙이 두 곳에 생기고, 한쪽만 고쳐지는 날 두 화면의 회차 목록이 갈립니다.
			""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "조회 성공. 담당 기수에 회차가 없으면 빈 배열"),
			@ApiResponse(responseCode = "403", description = "매니저 권한이 없음")
	})
	@GetMapping("/rounds")
	public ResponseEntity<List<InterviewRoundOptionResponse>> findRoundOptions(Authentication authentication) {
		List<InterviewRoundOptionResponse> options = interviewService
				.findRoundOptions(currentUserResolver.resolveCurrentMemberId(), ActorContext.organizationId(authentication))
				.stream()
				.map(InterviewRoundOptionResponse::from)
				.toList();
		return ResponseEntity.ok(options);
	}

	@Operation(operationId = "excludeInterviewCase", summary = "[면담 목록] 면담 대상 제외 | ✅ 사용 가능", description = """
			이번 회차 대상에서 뺍니다. **되돌릴 수 있습니다**(`DELETE`).

			화면이 확인 다이얼로그 없이 즉시 실행하고 배너로 되돌리기를 남기므로 **요청 본문이 없습니다.**
			`exclusion_reason_code`는 NOT NULL이라 서버가 기본값을 채웁니다.

			### 제외할 수 없는 경우

			연결된 면담이 이미 **시작·종결**됐으면 409입니다. 후보 상태 전이가
			`연결된 면담이 PENDING인 후보`로 한정돼 있습니다 — 이미 만난 사람을 큐에서 빼는 것은
			상태 모델상 의미가 없습니다.

			### 제외해도 지우지 않는 것

			`Interview`와 그 브리프·원천 행은 **그대로 보존**됩니다. 되돌리면 만들어 둔 브리프가
			그대로 살아납니다.
			""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "제외 완료"),
			@ApiResponse(responseCode = "404", description = "INTERVIEW_CASE_NOT_FOUND 담당 범위에서 찾을 수 없음"),
			@ApiResponse(responseCode = "409", description = """
					INTERVIEW_ALREADY_STARTED 이미 시작·종결된 면담
					· INTERVIEW_EXCLUSION_STATE_CONFLICT 이미 제외됨
					· INTERVIEW_ROW_VERSION_CONFLICT 그 사이 상태가 바뀜""")
	})
	@PostMapping("/{caseId}/exclusion")
	public ResponseEntity<Void> excludeCase(
			@Parameter(description = "면담 케이스 ID", required = true)
			@PathVariable UUID caseId,
			Authentication authentication
	) {
		interviewService.exclude(
				currentUserResolver.resolveCurrentMemberId(), ActorContext.organizationId(authentication), caseId);
		return ResponseEntity.noContent().build();
	}

	@Operation(operationId = "reincludeInterviewCase", summary = "[면담 목록] 면담 대상 제외 되돌리기 | ✅ 사용 가능", description = """
			제외를 되돌립니다.

			### 복귀 상태가 두 가지입니다

			| 조건 | 복귀 상태 |
			|---|---|
			| 연결된 면담이 남아 있다(브리프를 만든 뒤 제외했다) | `INTERVIEW_CREATED` |
			| 면담이 없다 | `ELIGIBLE` |

			어느 쪽이든 제외 속성 4개를 NULL로 되돌립니다.

			### 목록에 없어도 호출됩니다

			화면은 되돌리기 배너에서 **caseId만으로** 호출합니다 — 그 사이 필터를 바꿔
			현재 목록에 그 케이스가 안 보여도 동작해야 합니다.
			""")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "되돌리기 완료"),
			@ApiResponse(responseCode = "404", description = "INTERVIEW_CASE_NOT_FOUND 담당 범위에서 찾을 수 없음"),
			@ApiResponse(responseCode = "409", description = """
					INTERVIEW_EXCLUSION_STATE_CONFLICT 제외 상태가 아님
					· INTERVIEW_ROW_VERSION_CONFLICT 그 사이 상태가 바뀜""")
	})
	@DeleteMapping("/{caseId}/exclusion")
	public ResponseEntity<Void> reincludeCase(
			@Parameter(description = "면담 케이스 ID", required = true)
			@PathVariable UUID caseId,
			Authentication authentication
	) {
		interviewService.reinclude(
				currentUserResolver.resolveCurrentMemberId(), ActorContext.organizationId(authentication), caseId);
		return ResponseEntity.noContent().build();
	}

}
