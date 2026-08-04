package com.bigproject.backend.domain.manager.presentation;

import com.bigproject.backend.domain.manager.application.TraineeRosterService;
import com.bigproject.backend.domain.manager.domain.TraineeRosterSortMode;
import com.bigproject.backend.domain.manager.presentation.dto.TraineeRosterListResponse;
import com.bigproject.backend.domain.member.domain.AccountStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
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

@Tag(name = "Manager")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RestController
@RequestMapping("/cohorts/{cohortId}/manager/trainee-roster")
@RequiredArgsConstructor
public class ManagerTraineeRosterController {
	private final TraineeRosterService traineeRosterService;

	@Operation(
			summary = "MG-05 교육생 명부 조회",
			description = "매니저가 자신의 담당 반 범위에서 교육생 명부를 조회합니다. 프로젝트 회차·담당 반(전체/특정)·계정 활성화 "
					+ "여부·정렬 방식으로 필터링하며, 회차를 생략하면 기수의 최신 미니프로젝트 활성 회차를 기본값으로 사용합니다."
	)
	@PreAuthorize("hasRole('MANAGER')")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "교육생 명부 조회 성공"),
			@ApiResponse(responseCode = "400", description = "반·회차·페이지 값이 올바르지 않음"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 인증 사용자를 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "매니저 권한·계정 상태 또는 담당 반 범위가 허용되지 않음"),
			@ApiResponse(responseCode = "404", description = "기수 또는 조회 가능한 회차를 찾을 수 없음")
	})
	@GetMapping
	public ResponseEntity<TraineeRosterListResponse> findTraineeRoster(
			@Parameter(description = "교육생 명부를 조회할 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Parameter(description = "특정 반만 조회하며 생략 시 담당 반 전체를 조회합니다.", example = "123e4567-e89b-12d3-a456-426614174001")
			@RequestParam(required = false) UUID classId,
			@Parameter(description = "조회할 계정 표시 상태이며 생략 시 전체 상태를 조회합니다.", example = "ACTIVE")
			@RequestParam(required = false) AccountStatus accountStatus,
			@Parameter(description = "조회할 미니프로젝트 평가 회차 ID이며 생략 시 최신 회차를 사용합니다.",
					example = "123e4567-e89b-12d3-a456-426614174002")
			@RequestParam(required = false) UUID assessmentRoundId,
			@Parameter(description = "정렬 방식이며 생략 시 이름순(DEFAULT)입니다.", example = "DEFAULT")
			@RequestParam(defaultValue = "DEFAULT") TraineeRosterSortMode sortMode,
			@Parameter(description = "이름에 적용할 대소문자 무시 검색어입니다.", example = "하늘")
			@RequestParam(required = false) @Size(max = 200) String query,
			@Parameter(description = "0부터 시작하는 페이지 번호입니다.", example = "0")
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@Parameter(description = "페이지당 항목 수이며 1~100까지 허용합니다.", example = "20")
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		return ResponseEntity.ok(traineeRosterService.findRoster(
				cohortId,
				classId,
				accountStatus,
				assessmentRoundId,
				sortMode,
				query,
				page,
				size,
				authentication.getName()
		));
	}
}
