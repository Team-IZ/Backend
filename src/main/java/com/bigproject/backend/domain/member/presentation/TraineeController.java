package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.member.application.MemberInvitationService;
import com.bigproject.backend.domain.member.application.MemberQueryService;
import com.bigproject.backend.domain.member.application.TraineeCsvParser;
import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesRequest;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesResponse;
import com.bigproject.backend.domain.member.presentation.dto.TraineeListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Tag(name = "Trainee", description = "기수 교육생 명단 조회·등록 API")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RestController
@RequestMapping("/cohorts/{cohortId}/trainees")
@RequiredArgsConstructor
public class TraineeController {
	private static final String REQUEST_ID_HEADER = "X-Request-Id";

	private final MemberQueryService memberQueryService;
	private final MemberInvitationService memberInvitationService;
	private final TraineeCsvParser traineeCsvParser;

	@Operation(
			summary = "기수 교육생 명단 조회",
			description = "총괄·일반 매니저가 자기 기관의 선택 기수 전체 명단을 조회합니다. "
					+ "담당 반에 따른 세부 열람 제한은 적용하지 않으며, DB의 PENDING 상태는 INVITED로 노출합니다."
	)
	@PreAuthorize("hasAnyRole('LEAD_MANAGER', 'MANAGER')")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "기수 교육생 명단 조회 성공"),
			@ApiResponse(responseCode = "400", description = "반·상태·검색 또는 페이지 값이 올바르지 않음"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 인증 사용자를 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "역할·계정·기관 상태 또는 기수 접근 범위가 허용되지 않음"),
			@ApiResponse(responseCode = "404", description = "조회할 기수를 찾을 수 없음")
	})
	@GetMapping
	public ResponseEntity<TraineeListResponse> findTrainees(
			@Parameter(description = "교육생 명단을 조회할 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Parameter(description = "해당 반의 현재 소속 교육생만 조회하며 생략 시 전체 반을 조회합니다.", example = "123e4567-e89b-12d3-a456-426614174001")
			@RequestParam(required = false) UUID classroomId,
			@Parameter(description = "조회할 계정 상태이며 생략 시 모든 상태를 조회합니다.", example = "ACTIVE")
			@RequestParam(required = false) AccountStatus status,
			@Parameter(description = "이름 또는 이메일에 적용할 대소문자 무시 검색어입니다.", example = "trainee@example.com")
			@RequestParam(required = false) @Size(max = 200) String query,
			@Parameter(description = "0부터 시작하는 페이지 번호입니다.", example = "0")
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@Parameter(description = "페이지당 항목 수이며 1~100까지 허용합니다.", example = "20")
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		return ResponseEntity.ok(memberQueryService.findTrainees(
				cohortId,
				classroomId,
				status,
				query,
				page,
				size,
				authentication.getName()
		));
	}

	@Operation(
			summary = "CSV 교육생 명단 등록 및 초대",
			description = "총괄 매니저가 UTF-8 CSV 파일을 업로드하면 이름·이메일을 행별 검증한 뒤 "
					+ "교육생 계정과 초대 토큰을 생성하고 메일을 발송합니다. 첫 행은 '이름,이메일' 헤더여야 합니다."
	)
	@PreAuthorize("hasRole('LEAD_MANAGER')")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "교육생 등록·초대 처리 완료; CSV 행별 실패는 응답 본문에 포함"),
			@ApiResponse(responseCode = "400", description = "CSV 파일·헤더·인코딩 또는 열 구성이 올바르지 않음"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 인증 사용자를 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "총괄 매니저 권한·계정 상태 또는 기관 범위가 허용되지 않음"),
			@ApiResponse(responseCode = "404", description = "등록 가능한 기수를 찾을 수 없음")
	})
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<RegisterTraineesResponse> registerTraineesFromCsv(
			@Parameter(description = "교육생을 등록할 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Parameter(description = "첫 행이 '이름,이메일'인 UTF-8 CSV 파일")
			@RequestPart("file") MultipartFile file,
			@Parameter(description = "일괄 등록 요청 추적용 식별자이며 생략 시 서버가 생성합니다.", example = "trainee-batch-001")
			@RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		return ResponseEntity.status(HttpStatus.CREATED).body(memberInvitationService.inviteTraineesFromCsv(
				cohortId,
				traineeCsvParser.parse(file),
				authentication.getName(),
				requestId
		));
	}

	@Operation(
			summary = "직접 입력 교육생 등록 및 초대",
			description = "총괄 매니저가 이름·이메일을 여러 행으로 직접 입력하면 행별로 교육생 계정과 "
					+ "초대 토큰을 생성하고 메일을 발송합니다. CSV 업로드와 별도 초대 경로로 구분합니다."
	)
	@PreAuthorize("hasRole('LEAD_MANAGER')")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "교육생 등록·초대 처리 완료; 행별 실패는 응답 본문에 포함"),
			@ApiResponse(responseCode = "400", description = "교육생 목록·이름·이메일 값이 올바르지 않음"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 인증 사용자를 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "총괄 매니저 권한·계정 상태 또는 기관 범위가 허용되지 않음"),
			@ApiResponse(responseCode = "404", description = "등록 가능한 기수를 찾을 수 없음")
	})
	@PostMapping(path = "/invitations", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<RegisterTraineesResponse> registerTrainees(
			@Parameter(description = "교육생을 등록할 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Valid @RequestBody RegisterTraineesRequest request,
			@Parameter(description = "일괄 등록 요청 추적용 식별자이며 생략 시 서버가 생성합니다.", example = "trainee-direct-001")
			@RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		return ResponseEntity.status(HttpStatus.CREATED).body(memberInvitationService.inviteTrainees(
				cohortId,
				request,
				authentication.getName(),
				requestId
		));
	}
}
