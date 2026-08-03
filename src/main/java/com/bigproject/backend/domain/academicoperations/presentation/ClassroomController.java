package com.bigproject.backend.domain.academicoperations.presentation;

import com.bigproject.backend.domain.academicoperations.application.ClassroomService;
import com.bigproject.backend.domain.academicoperations.presentation.dto.AssignTraineesRequest;
import com.bigproject.backend.domain.academicoperations.presentation.dto.AssignTraineesResponse;
import com.bigproject.backend.domain.academicoperations.presentation.dto.ClassroomListResponse;
import com.bigproject.backend.domain.academicoperations.presentation.dto.ClassroomResponse;
import com.bigproject.backend.domain.academicoperations.presentation.dto.CreateClassroomRequest;
import com.bigproject.backend.domain.academicoperations.presentation.dto.UpdateClassroomManagersRequest;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

// TODO: auth 도메인(PR #9, #11, #13) 병합 확인 결과 반영.
// - organizationId: auth 도메인 병합 완료. JwtFilter가 authentication.setDetails(jwtProvider.getOrganizationId(token))로
//   organizationId(UUID)를 details에 담아주는 것을 확인함. 현재 구현(extractOrganizationId)이 이 구조와 일치함.
// - actorUserId(created_by): 토큰에 요청자 UUID가 담기지 않아 임시로 X-Actor-User-Id 헤더로 받고 있음.
//   클라이언트가 임의의 UUID를 보낼 수 있는 구조이므로,
//   인증 주체에서 요청자 UUID를 얻는 방법이 생기면 반드시 교체할 것.
@Tag(name = "Classroom", description = "기수 반 편성과 교육생 배정 API")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/cohorts/{cohortId}/classrooms")
@RequiredArgsConstructor
public class ClassroomController {

	private final ClassroomService classroomService;

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

	@Operation(summary = "반 생성")
	@PreAuthorize("hasRole('OPERATOR')")
	@PostMapping
	public ResponseEntity<ClassroomResponse> createClassroom(
			@PathVariable UUID cohortId,
			@Valid @RequestBody CreateClassroomRequest request,
			Authentication authentication,
			@RequestHeader("X-Actor-User-Id") UUID actorUserId
	) {
		UUID organizationId = extractOrganizationId(authentication);
		ClassroomService.ClassroomView view = classroomService.createClassroom(
				organizationId, cohortId, request.name(), request.capacity(), actorUserId);
		return ResponseEntity.status(HttpStatus.CREATED).body(ClassroomResponse.from(view));
	}

	@Operation(summary = "반 담당 매니저 변경")
	@PreAuthorize("hasRole('OPERATOR')")
	@PatchMapping("/{classroomId}/managers")
	public ResponseEntity<ClassroomResponse> updateManagers(
			@PathVariable UUID cohortId,
			@PathVariable UUID classroomId,
			@Valid @RequestBody UpdateClassroomManagersRequest request,
			Authentication authentication,
			@RequestHeader("X-Actor-User-Id") UUID actorUserId
	) {
		UUID organizationId = extractOrganizationId(authentication);
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
			Authentication authentication,
			@RequestHeader("X-Actor-User-Id") UUID actorUserId
	) {
		UUID organizationId = extractOrganizationId(authentication);
		List<UUID> assignedTraineeIds = classroomService.assignTrainees(
				cohortId, request.classroomId(), request.traineeIds(), organizationId, actorUserId);
		return ResponseEntity.ok(new AssignTraineesResponse(request.classroomId(), assignedTraineeIds, assignedTraineeIds.size()));
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
