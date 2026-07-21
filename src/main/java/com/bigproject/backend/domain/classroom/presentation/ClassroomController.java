package com.bigproject.backend.domain.classroom.presentation;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Classroom", description = "기수 반 편성과 교육생 배정 API")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/cohorts/{cohortId}/classrooms")
public class ClassroomController {

	@Operation(summary = "기수 반 목록 조회")
	@GetMapping
	public ResponseEntity<ClassroomListResponse> findClassrooms(@PathVariable Long cohortId) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	@Operation(summary = "반 생성")
	@PreAuthorize("hasRole('LEAD_MANAGER')")
	@PostMapping
	public ResponseEntity<ClassroomResponse> createClassroom(
			@PathVariable Long cohortId,
			@Valid @RequestBody CreateClassroomRequest request
	) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	@Operation(summary = "반 담당 매니저 변경")
	@PreAuthorize("hasRole('LEAD_MANAGER')")
	@PatchMapping("/{classroomId}/managers")
	public ResponseEntity<ClassroomResponse> updateManagers(
			@PathVariable Long cohortId,
			@PathVariable Long classroomId,
			@Valid @RequestBody UpdateClassroomManagersRequest request
	) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	@Operation(summary = "교육생 일괄 반 배정")
	@PreAuthorize("hasRole('LEAD_MANAGER')")
	@PatchMapping("/trainee-assignments")
	public ResponseEntity<AssignTraineesResponse> assignTrainees(
			@PathVariable Long cohortId,
			@Valid @RequestBody AssignTraineesRequest request
	) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}
}
