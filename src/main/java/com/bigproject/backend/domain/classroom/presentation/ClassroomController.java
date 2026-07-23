package com.bigproject.backend.domain.classroom.presentation;

import com.bigproject.backend.domain.classroom.application.ClassroomService;
import com.bigproject.backend.domain.classroom.domain.Classroom;
import com.bigproject.backend.domain.classroom.presentation.dto.AssignTraineesRequest;
import com.bigproject.backend.domain.classroom.presentation.dto.AssignTraineesResponse;
import com.bigproject.backend.domain.classroom.presentation.dto.ClassroomListResponse;
import com.bigproject.backend.domain.classroom.presentation.dto.ClassroomResponse;
import com.bigproject.backend.domain.classroom.presentation.dto.CreateClassroomRequest;
import com.bigproject.backend.domain.classroom.presentation.dto.UpdateClassroomManagersRequest;
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

// TODO: auth 도메인(PR #9, #11, #13)이 develop에 아직 병합되지 않은 상태에서 작성됨.
// - organizationId: JwtFilter가 authentication.getDetails()에 UUID를 담아준다는 전제로 구현했지만,
//   현재 머지된 JwtProvider.getOrganizationId()는 Long을 반환하고 JwtFilter도 그 Long을 그대로 details에 넣는다.
//   auth 도메인 병합 시 JWT의 organizationId 클레임과 JwtProvider/JwtFilter가 UUID 기준으로 함께 바뀌는지 반드시 확인할 것.
//   (지금 상태로 실제 토큰이 들어오면 UUID 캐스팅에서 ClassCastException 발생)
// - actorUserId(created_by): 토큰에 요청자 UUID가 담기지 않아 임시로 X-Actor-User-Id 헤더로 받고 있음.
//   클라이언트가 임의의 UUID를 보낼 수 있는 구조이므로,
//   auth 도메인 병합 후 인증 주체에서 추출하도록 반드시 교체할 것.
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
		List<ClassroomResponse> classrooms = classroomService.findClassrooms(cohortId, organizationId).stream()
				.map(ClassroomResponse::from)
				.toList();
		return ResponseEntity.ok(new ClassroomListResponse(classrooms));
	}

	@Operation(summary = "반 생성")
	@PreAuthorize("hasRole('LEAD_MANAGER')")
	@PostMapping
	public ResponseEntity<ClassroomResponse> createClassroom(
			@PathVariable UUID cohortId,
			@Valid @RequestBody CreateClassroomRequest request,
			Authentication authentication,
			@RequestHeader("X-Actor-User-Id") UUID actorUserId
	) {
		UUID organizationId = extractOrganizationId(authentication);
		Classroom classroom = classroomService.createClassroom(organizationId, cohortId, request.name(), actorUserId);
		return ResponseEntity.status(HttpStatus.CREATED).body(ClassroomResponse.from(classroom));
	}

	@Operation(summary = "반 담당 매니저 변경")
	@PreAuthorize("hasRole('LEAD_MANAGER')")
	@PatchMapping("/{classroomId}/managers")
	public ResponseEntity<ClassroomResponse> updateManagers(
			@PathVariable UUID cohortId,
			@PathVariable UUID classroomId,
			@Valid @RequestBody UpdateClassroomManagersRequest request
	) {
		// TODO: manager_assignment 테이블이 week04 DDL에 없어 구현 불가. 테이블 추가 후 구현할 것.
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	@Operation(summary = "교육생 일괄 반 배정")
	@PreAuthorize("hasRole('LEAD_MANAGER')")
	@PatchMapping("/trainee-assignments")
	public ResponseEntity<AssignTraineesResponse> assignTrainees(
			@PathVariable UUID cohortId,
			@Valid @RequestBody AssignTraineesRequest request
	) {
		// TODO: cohort_member, class_membership 도메인이 아직 구현되지 않아 구현 불가.
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	// JwtFilter가 organizationId(UUID)를 authentication.getDetails()에 담아준다는 전제로 추출
	private UUID extractOrganizationId(Authentication authentication) {
		Object details = authentication.getDetails();
		if (!(details instanceof UUID organizationId)) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
					"인증 정보에서 organizationId(UUID)를 확인할 수 없습니다.");
		}
		return organizationId;
	}
}
