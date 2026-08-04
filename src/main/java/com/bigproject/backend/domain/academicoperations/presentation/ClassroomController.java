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

	@Operation(summary = "기수 반 목록 조회")
	@GetMapping
	public ResponseEntity<ClassroomListResponse> findClassrooms(
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
	@Operation(summary = "반 생성")
	@PreAuthorize("hasRole('OPERATOR')")
	@PostMapping
	public ResponseEntity<ClassroomResponse> createClassroom(
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

	@Operation(summary = "반 담당 매니저 변경")
	@PreAuthorize("hasRole('OPERATOR')")
	@PatchMapping("/{classroomId}/managers")
	public ResponseEntity<ClassroomResponse> updateManagers(
			@PathVariable UUID cohortId,
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

	@Operation(summary = "교육생 일괄 반 배정")
	@PreAuthorize("hasRole('OPERATOR')")
	@PatchMapping("/trainee-assignments")
	public ResponseEntity<AssignTraineesResponse> assignTrainees(
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

	@Operation(summary = "교육생 반 배정 되돌리기")
	@PreAuthorize("hasRole('OPERATOR')")
	@PatchMapping("/trainee-assignments/rollback")
	public ResponseEntity<RollbackAssignmentResponse> rollbackAssignment(
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