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
			summary = "반별 제출·분석·응시 현황 조회 | ✅ 사용 가능",
			description = """
					프로젝트 회차의 반별 진행을 제출 → 분석 → 응시 순으로 한 번에 조회합니다.

					단계별 깔때기라 각 단계의 분모가 앞 단계의 분자입니다.
					제출률은 submittedCount / targetTraineeCount,
					응시율은 assessedCount / analysisSucceededCount 입니다.
					응시율의 분모가 제출 단계에서 나오므로 두 지표를 나눠 호출하지 않습니다.

					제출은 팀 단위 원장이지만 이 화면은 인원 기준으로 환산합니다.

					분석 상태는 성공·실패·부분 성공·진행 중 네 갈래를 모두 내려줍니다.
					부분 성공(PARTIAL)은 분석 완료로 세지 않으므로 응시율 분모에서 빠집니다.
					네 값을 더하면 제출 인원과 같아 어느 열에도 잡히지 않고 사라지는 인원이 없습니다.

					회차는 projectId와 roundNo로 특정합니다. round_no는 프로젝트 안에서만 유일합니다.
					"""
	)
	@PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "반별 현황 조회 성공"),
			@ApiResponse(responseCode = "400", description = "회차 번호가 올바르지 않음"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 인증 사용자를 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "역할·계정·기관 상태 또는 프로젝트 접근 범위가 허용되지 않음"),
			@ApiResponse(responseCode = "404", description = "조회할 프로젝트 회차를 찾을 수 없음")
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
