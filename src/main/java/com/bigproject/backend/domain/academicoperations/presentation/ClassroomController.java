package com.bigproject.backend.domain.academicoperations.presentation;

import com.bigproject.backend.domain.academicoperations.application.ClassroomService;
import com.bigproject.backend.domain.academicoperations.presentation.dto.AssignTraineesRequest;
import com.bigproject.backend.domain.academicoperations.presentation.dto.AssignTraineesResponse;
import com.bigproject.backend.domain.academicoperations.presentation.dto.ClassroomListResponse;
import com.bigproject.backend.domain.academicoperations.presentation.dto.ClassroomResponse;
import com.bigproject.backend.domain.academicoperations.presentation.dto.CreateClassroomRequest;
import com.bigproject.backend.domain.academicoperations.presentation.dto.RollbackAssignmentRequest;
import com.bigproject.backend.domain.academicoperations.presentation.dto.RollbackAssignmentResponse;
import com.bigproject.backend.domain.academicoperations.presentation.dto.UpdateClassroomManagersRequest;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

// organizationId: JwtFilter가 authentication.setDetails(...)로 넣어준 값을 꺼낸다.
//
// actorUserId: 이전에는 X-Actor-User-Id 헤더로 받았으나 CurrentUserResolver로 교체했다.
//   헤더 방식은 클라이언트가 임의의 UUID를 보낼 수 있어서 class.created_by / class_membership.assigned_by
//   같은 감사 필드를 남의 이름으로 위조할 수 있었다. 감사 필드는 요청에서 받지 않는다.
//   CurrentUserResolver는 organization·operations 도메인이 이미 쓰고 있는 공용 컴포넌트다.
@Tag(name = "Academic Operations")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/cohorts/{cohortId}/classrooms")
@RequiredArgsConstructor
public class ClassroomController {

	private final ClassroomService classroomService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(
			summary = "기수 반 목록 조회 | ✅ 사용 가능",
			description = """
					기수에 편성된 반 전체를 조회한다. 조회 범위인 기관은 액세스 토큰에서 가져온다.

					**아직 채워지지 않는 값** — managers[].name은 항상 빈 문자열이다.
					매니저 이름은 app_user 조인이 필요해 member 도메인 의존이 생기므로 memberId만 내려준다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "반 목록 조회 성공"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "404", description = "기수를 찾을 수 없음(다른 기관의 기수 포함)"),
			@ApiResponse(responseCode = "500", description = "인증 정보에서 organizationId를 확인할 수 없음")
	})
	@GetMapping
	public ResponseEntity<ClassroomListResponse> findClassrooms(
			@Parameter(description = "반을 조회할 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			Authentication authentication
	) {
		UUID organizationId = extractOrganizationId(authentication);
		List<ClassroomResponse> classrooms = classroomService.findClassroomViews(cohortId, organizationId).stream()
				.map(ClassroomResponse::from)
				.toList();
		return ResponseEntity.ok(new ClassroomListResponse(classrooms));
	}

	// 반 생성 시 담당 매니저를 함께 저장한다.
	// 이전에는 request.managerIds()를 서비스로 넘기지 않아, 반 추가 모델에서 매니저를 골라도
	// 에러 없이 조용히 버려졌다.
	@Operation(
			summary = "반 생성 | ✅ 사용 가능",
			description = """
					오퍼레이터가 기수 안에 반을 만든다.

					⚠️ `managerIds`를 요청에 넣어도 **적용되지 않는다.** 스키마에는 남아 있지만 서버가 사용하지 않으며,
					담당 매니저 지정은 `PATCH /cohorts/{cohortId}/classrooms/{classroomId}/managers`로 따로 호출해야 한다.

					⚠️ `X-Actor-User-Id`는 인증 연동 전의 임시 헤더다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "반 생성 성공"),
			@ApiResponse(responseCode = "400", description = "반 이름이 비었거나 정원이 1 미만임"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "오퍼레이터 권한이 없음"),
			@ApiResponse(responseCode = "404", description = "기수를 찾을 수 없음(다른 기관의 기수 포함)")
	})
	@PreAuthorize("hasRole('OPERATOR')")
	@PostMapping
	public ResponseEntity<ClassroomResponse> createClassroom(
			@Parameter(description = "반을 만들 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Valid @RequestBody CreateClassroomRequest request,
			Authentication authentication
	) {
		UUID organizationId = extractOrganizationId(authentication);
		UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
		ClassroomService.ClassroomView view = classroomService.createClassroom(
				organizationId, cohortId, request.name(), request.capacity(), request.managerIds(), actorUserId);
		return ResponseEntity.status(HttpStatus.CREATED).body(ClassroomResponse.from(view));
	}

	@Operation(
			summary = "반 담당 매니저 변경 | ✅ 사용 가능",
			description = """
					반의 담당 매니저를 **전체 교체**한다. 부분 추가·삭제가 아니라 보낸 목록이 그대로 최종 상태가 된다 —
					기존 활성 배정을 모두 해제(사유 `REASSIGNED`)한 뒤 요청받은 매니저로 새 배정을 만든다.
					**빈 배열을 보내면 전체 해제**가 되어 담당자가 없는 반이 된다.

					해제된 배정은 지워지지 않고 이력으로 남는다 — 과거 기수의 담당자를 추적할 수 있어야 하기 때문이다.

					⚠️ managerIds에 담긴 UUID가 **실제 매니저인지 검증하지 않는다.** 존재하지 않거나 다른 역할인 사용자
					ID를 보내도 배정 행이 만들어진다. 화면은 매니저 목록에서 고른 값만 보내야 한다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "담당 매니저 변경 성공"),
			@ApiResponse(responseCode = "400", description = "managerIds가 누락됨"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "오퍼레이터 권한이 없음"),
			@ApiResponse(responseCode = "404", description = "반을 찾을 수 없거나 지정한 기수에 속하지 않음")
	})
	@PreAuthorize("hasRole('OPERATOR')")
	@PatchMapping("/{classroomId}/managers")
	public ResponseEntity<ClassroomResponse> updateManagers(
			@Parameter(description = "대상 반이 속한 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Parameter(description = "담당 매니저를 바꿀 반 ID. cohortId 소속이 아니면 404", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID classroomId,
			@Valid @RequestBody UpdateClassroomManagersRequest request,
			Authentication authentication
	) {
		UUID organizationId = extractOrganizationId(authentication);
		UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
		ClassroomService.ClassroomView view = classroomService.updateClassroomManagers(
				cohortId, classroomId, organizationId, request.managerIds(), actorUserId);
		return ResponseEntity.ok(ClassroomResponse.from(view));
	}

	@Operation(
			summary = "교육생 일괄 반 배정 | ✅ 사용 가능",
			description = """
					교육생 여러 명을 한 반으로 **옮긴다**(이동 배정). 대상자가 이미 다른 반에 있으면 그 배정을
					해제(사유 `REASSIGNED`)한 뒤 새 반에 넣으므로, 교육생은 항상 기수 안에서 반 하나에만 속한다.

					**전부 성공하거나 전부 실패한다.** 요청한 교육생 중 한 명이라도 그 기수 소속이 아니면
					배정을 시작하지 않고 400으로 거절하며, 어느 ID가 문제인지 메시지에 담는다 —
					일부만 반영되면 어디까지 처리됐는지 화면에서 알 수 없기 때문이다.

					⚠️ 정원(capacity)을 초과해도 막지 않는다. 반 정원은 현재 표시용 값이다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "반 배정 성공"),
			@ApiResponse(responseCode = "400", description = "traineeIds가 비었거나 기수에 속하지 않은 교육생이 포함됨"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "오퍼레이터 권한이 없음"),
			@ApiResponse(responseCode = "404", description = "반을 찾을 수 없거나 지정한 기수에 속하지 않음")
	})
	@PreAuthorize("hasRole('OPERATOR')")
	@PatchMapping("/trainee-assignments")
	public ResponseEntity<AssignTraineesResponse> assignTrainees(
			@Parameter(description = "대상 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Valid @RequestBody AssignTraineesRequest request,
			Authentication authentication
	) {
		UUID organizationId = extractOrganizationId(authentication);
		UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
		List<UUID> assignedTraineeIds = classroomService.assignTrainees(
				cohortId, request.classroomId(), request.traineeIds(), organizationId, actorUserId);
		return ResponseEntity.ok(new AssignTraineesResponse(request.classroomId(), assignedTraineeIds, assignedTraineeIds.size()));
	}

	@Operation(
			summary = "교육생 반 배정 되돌리기 | ✅ 사용 가능",
			description = """
					교육생의 현재 반 배정을 **해제만** 한다(사유 `IMMEDIATE_ROLLBACK`). `assignTrainees`(이동 배정)와
					달리 새 배정을 만들지 않으므로, 처리 후 해당 교육생은 어느 반에도 속하지 않는 상태가 된다.

					**전부 성공하거나 전부 실패한다.** 요청한 교육생 중 한 명이라도 그 기수 소속이 아니거나
					**현재 활성 배정이 없으면**(이미 무소속이면) 처리를 시작하지 않고 400으로 거절한다 — 이미
					무소속인 사람을 "되돌리기"하는 건 의미 있는 동작이 아니기 때문이다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "반 배정 되돌리기 성공"),
			@ApiResponse(responseCode = "400", description = "traineeIds가 비었거나 기수에 속하지 않은 교육생 포함, 또는 현재 활성 배정이 없는 교육생 포함"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "오퍼레이터 권한이 없음"),
			@ApiResponse(responseCode = "404", description = "기수를 찾을 수 없음(다른 기관의 기수 포함)")
	})
	@PreAuthorize("hasRole('OPERATOR')")
	@PatchMapping("/trainee-assignments/rollback")
	public ResponseEntity<RollbackAssignmentResponse> rollbackAssignment(
			@Parameter(description = "대상 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Valid @RequestBody RollbackAssignmentRequest request,
			Authentication authentication
	) {
		UUID organizationId = extractOrganizationId(authentication);
		UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
		List<UUID> rolledBackTraineeIds = classroomService.rollbackAssignment(
				cohortId, request.traineeIds(), organizationId, actorUserId);
		return ResponseEntity.ok(new RollbackAssignmentResponse(rolledBackTraineeIds, rolledBackTraineeIds.size()));
	}

	// JwtFilter가 authentication.getDetails()에 담아준 organizationId(UUID)를 추출
	private UUID extractOrganizationId(Authentication authentication) {
		Object details = authentication.getDetails();
		if (!(details instanceof UUID organizationId)) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
					"인증 정보에서 organizationId(UUID)를 확인할 수 없습니다.");
		}
		return organizationId;
	}
}