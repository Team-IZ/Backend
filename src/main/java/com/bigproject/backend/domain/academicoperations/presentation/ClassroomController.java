package com.bigproject.backend.domain.academicoperations.presentation;

import com.bigproject.backend.domain.academicoperations.domain.AcademicOperationsErrorCode;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.domain.academicoperations.application.ClassroomService;
import com.bigproject.backend.domain.academicoperations.presentation.dto.AssignTraineesRequest;
import com.bigproject.backend.domain.academicoperations.presentation.dto.AssignTraineesResponse;
import com.bigproject.backend.domain.academicoperations.presentation.dto.ClassroomListResponse;
import com.bigproject.backend.domain.academicoperations.presentation.dto.ClassroomResponse;
import com.bigproject.backend.domain.academicoperations.presentation.dto.CreateClassroomRequest;
import com.bigproject.backend.domain.academicoperations.presentation.dto.RollbackAssignmentRequest;
import com.bigproject.backend.domain.academicoperations.presentation.dto.RollbackAssignmentResponse;
import com.bigproject.backend.domain.academicoperations.presentation.dto.UpdateClassroomManagersRequest;
import com.bigproject.backend.domain.academicoperations.presentation.dto.UpdateClassroomRequest;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
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

@Tag(name = "Academic Operations")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping(value = "/cohorts/{cohortId}/classrooms", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ClassroomController {

	private static final String MANAGER_ROLE = "ROLE_MANAGER";

	private final ClassroomService classroomService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(
			operationId = "findClassrooms",
			summary = "기수 반 목록 조회 | ✅ 사용 가능",
			description = """
					기수에 편성된 반을 조회한다. 조회 범위인 기관은 액세스 토큰에서 가져온다.

					**역할에 따라 범위가 다르다.** 오퍼레이터는 기수의 반 전체를, **매니저는 자신이 현재
					담당하는 반만** 본다(`manager_assignment`가 ACTIVE인 반).

					매니저를 좁히는 이유는 이 목록이 교육생 명단 화면의 `반 · 전체` 드롭다운을 채우기 때문이다 —
					`GET /cohorts/{cohortId}/trainees`가 매니저에게 담당 반 교육생만 주는데 드롭다운만 기수 전체
					10개를 보여주면, 고를 수는 있는데 고르면 늘 비는 반이 생긴다. **두 API는 같은 모집단이어야 한다.**

					**요청**
					- cohortId (경로): 반을 조회할 기수 ID

					**응답 (200)**
					- classrooms[].classroomId: 반 ID
					- classrooms[].cohortId: 소속 기수 ID
					- classrooms[].name: 반 이름
					- classrooms[].capacity: 정원. 반 카드의 `24 / 30명`에서 분모(9차 R6)
					- classrooms[].managers[]: 담당 매니저 목록(memberId, name, email)
					- classrooms[].traineeCount: 현재 그 반에 소속된 교육생 수
					- classrooms[].managerAssignmentRequired: 담당 매니저가 없으면 true — 화면의 `매니저 미배정` 표시 근거

					**managers[].memberId는 UUID다.** `GET /managers`의 `managerId`,
					`PATCH …/managers`의 `managerIds[]`와 같은 값·같은 타입이라, 여기서 읽은 현재 담당을
					그대로 다시 보낼 수 있다(9차 R5).

					**managers[].name은 초대만 받고 아직 가입하지 않은 매니저에서 null이다.** 이름은 수락할 때
					본인이 넣는 값이라 그 전에는 비어 있다. `email`은 항상 온다 — 동명이인을 화면에서 가르는 값이다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "반 목록 조회 성공"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND 기수를 찾을 수 없음(다른 기관의 기수 포함)"),
			@ApiResponse(responseCode = "500", description = "ORGANIZATION_CONTEXT_MISSING 인증 정보에서 organizationId를 확인할 수 없음")
	})
	@GetMapping
	public ResponseEntity<ClassroomListResponse> findClassrooms(
			@Parameter(description = "반을 조회할 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			Authentication authentication
	) {
		UUID organizationId = extractOrganizationId(authentication);

		// 매니저는 담당 반만 본다. 이 목록이 명단 화면의 `반 · 전체` 드롭다운을 채우므로
		// 교육생 명단 조회의 매니저 스코프와 같은 모집단이어야 한다 — 드롭다운만 기수 전체를
		// 보여주면 고를 수는 있는데 고르면 늘 비는 반이 생긴다.
		boolean manager = authentication.getAuthorities().stream()
				.anyMatch(authority -> MANAGER_ROLE.equals(authority.getAuthority()));
		UUID scopedManagerId = manager ? currentUserResolver.resolveCurrentMemberId() : null;

		List<ClassroomResponse> classrooms = classroomService
				.findClassroomViews(cohortId, organizationId, scopedManagerId).stream()
				.map(ClassroomResponse::from)
				.toList();
		return ResponseEntity.ok(new ClassroomListResponse(classrooms));
	}

	// 반 생성 시 담당 매니저를 함께 저장한다.
	// 이전에는 request.managerIds()를 서비스로 넘기지 않아, 반 추가 모델에서 매니저를 골라도
	// 에러 없이 조용히 버려졌다.
	@Operation(
			operationId = "createClassroom",
			summary = "반 생성 | ✅ 사용 가능",
			description = """
					오퍼레이터가 기수 안에 반을 만든다.

					**요청**
					- cohortId (경로): 반을 만들 기수 ID
					- name (필수): 반 이름. 같은 기수 안에서 중복되면 **409 `CLASSROOM_NAME_TAKEN`**
					  (25차 R3 — 코드 이름이 응답 목록에 없어서 화면이 분기할 근거가 없었다.
					  수정(`PATCH …/{classroomId}`)과 같은 코드다)
					- capacity (필수, 1 이상): 정원
					- managerIds (선택): 담당 매니저로 지정할 사용자 ID 목록. **반 생성과 같은 트랜잭션에서 배정된다**.
					  이 기관의 매니저가 아닌 ID가 있으면 반도 만들지 않고 `404 MANAGER_NOT_FOUND`다(25차 R3)

					**응답 (201)**
					- 생성된 반 정보(응답 필드는 "기수 반 목록 조회"의 classrooms[] 항목과 동일).
					  `managerIds`를 보냈으면 `managers[]`가 채워져 오고 `managerAssignmentRequired=false`다.
					  갓 만든 반이라 `traineeCount`는 항상 0이다

					✅ **11차 Q3 — `managerIds`는 실제로 적용된다.** 예전 설명이 "서버가 사용하지 않는다"고
					적고 있었는데 사실과 달랐다(그 문장을 보고 화면이 입력 칸을 지웠다). 반 생성과 매니저 배정이
					**한 트랜잭션**이라 중간에 실패해도 "반만 있고 담당은 없는" 상태가 남지 않는다.
					`PATCH .../managers`는 이미 만든 반의 담당을 <b>나중에 바꿀 때</b> 쓴다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "반 생성 성공"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED 반 이름이 비었거나 정원이 1 미만임"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "오퍼레이터 권한이 없음"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND 기수를 찾을 수 없음(다른 기관의 기수 포함) · MANAGER_NOT_FOUND managerIds에 이 기관의 매니저가 아닌 ID가 포함됨(25차 R3)"),
			@ApiResponse(responseCode = "409", description = "CLASSROOM_NAME_TAKEN 같은 기수에 이미 있는 반 이름(25차 R3 — 동작은 처음부터 이랬고 목록에만 빠져 있었다)")
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
			operationId = "updateClassroom",
			summary = "반 수정 | ✅ 사용 가능",
			description = """
					반의 이름·정원을 고친다. **부분 수정이라 보낸 필드만 바뀐다.**

					**요청**
					- cohortId / classroomId (경로): 대상 반. classroomId가 그 기수 소속이 아니면 404
					- name (선택): 새 반 이름
					- capacity (선택): 새 정원. 1 이상

					**둘 다 생략하면 400** `CLASSROOM_UPDATE_EMPTY`다 — 아무 일도 하지 않는 요청이 200으로
					돌아오면 화면은 저장됐다고 오해한다. `name`에 공백만 보내는 것은 "안 바꾼다"와 같게 본다.

					**응답 (200)**
					- 변경 후의 반 정보(응답 필드는 "기수 반 목록 조회"의 classrooms[] 항목과 동일)

					## 이름 중복은 자기 자신을 빼고 본다

					생성과 같은 규칙(같은 기수 안에서 중복이면 409 `CLASSROOM_NAME_TAKEN`)이되
					**자기 자신은 제외**한다 — 이름은 그대로 두고 정원만 고치는 경우가 흔한데,
					자기 자신을 세면 그때마다 409가 난다.

					## 정원을 현재 인원보다 작게 두는 것을 막지 않는다

					정원 초과는 배정 경로(`PATCH …/trainee-assignments`)가 이미 허용하는 상태라
					(중도 합류·반 통폐합) 여기서만 막으면 규칙이 두 벌이 된다.

					⚠️ **개강 이후에도 수정은 열려 있다.** 오타 정정처럼 되돌릴 수 없는 값이 아니기 때문이다.
					화면이 `rules.ts`의 `canEditClasses`로 개강 전에만 여는 것은 그대로 두셔도 되고,
					서버는 그 시점을 이유로 거절하지 않는다. 되돌릴 수 없는 조작인 **삭제**만 서버가 판정한다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "반 수정 성공"),
			@ApiResponse(responseCode = "400", description = "CLASSROOM_UPDATE_EMPTY 바꿀 값이 하나도 없음 · VALIDATION_FAILED 정원이 1 미만"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "오퍼레이터 권한이 없음"),
			@ApiResponse(responseCode = "404", description = "CLASSROOM_NOT_FOUND 반을 찾을 수 없거나 지정한 기수에 속하지 않음"),
			@ApiResponse(responseCode = "409", description = "CLASSROOM_NAME_TAKEN 같은 기수에 이미 있는 반 이름(자기 자신은 제외하고 판정한다)")
	})
	@PreAuthorize("hasRole('OPERATOR')")
	@PatchMapping("/{classroomId}")
	public ResponseEntity<ClassroomResponse> updateClassroom(
			@Parameter(description = "대상 반이 속한 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Parameter(description = "수정할 반 ID. cohortId 소속이 아니면 404", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID classroomId,
			@Valid @RequestBody UpdateClassroomRequest request,
			Authentication authentication
	) {
		UUID organizationId = extractOrganizationId(authentication);
		ClassroomService.ClassroomView view = classroomService.updateClassroom(
				cohortId, classroomId, organizationId, request.name(), request.capacity());
		return ResponseEntity.ok(ClassroomResponse.from(view));
	}

	@Operation(
			operationId = "deleteClassroom",
			summary = "반 삭제 | ✅ 사용 가능",
			description = """
					반을 삭제한다. 데이터를 지우지 않고 삭제 시각만 기록하는 **소프트 삭제**이며,
					목록·조회에서 곧바로 빠진다.

					**요청**
					- cohortId / classroomId (경로): 대상 반. classroomId가 그 기수 소속이 아니면 404

					**응답 (204)** — 본문 없음

					## 안에 있던 교육생은 지우지 않는다

					**미배정으로 되돌린다**(사유 `ADMIN_CORRECTION`). 명단에서 지우면 그 사람의 등록 이력이
					끊긴다. 담당 매니저 배정도 함께 해제한다(사유 `CLASS_CLOSED`) — 없어진 반의 담당으로
					남아 있으면 매니저 목록의 `classroomNames`에 계속 잡힌다.

					해제된 배정은 지워지지 않고 종료 시각·사유와 함께 이력으로 남는다.

					## 삭제 가능 조건은 서버가 판정한다

					| 막는 조건 | 왜 |
					|---|---|
					| 반에 편성된 팀이 있다 | 팀이 붙었다는 것은 회차가 돌기 시작했다는 뜻이다. 제출·분석·응시가 그 팀에 매여 있다 |
					| 반을 대상으로 만들어진 리포트가 있다 | 발행된 리포트가 가리키는 반이 사라진다 |

					둘 다 409 `CLASSROOM_NOT_DELETABLE`이고, **무엇이 붙어 있는지는 `message`에 담는다** —
					어느 쪽이든 화면이 할 일은 "지울 수 없습니다"를 보여주고 버튼을 잠그는 하나라서 코드를 쪼개지 않았다.

					💡 **"개강했는가"를 기수 상태로 묻지 않는다.** 상태는 운영자가 손으로 바꾸는 값이라
					개강 전으로 되돌려 두고 지우면 데이터가 끊긴다. 팀·리포트는 되돌릴 수 없는 사실이라
					그쪽을 본다. 화면의 `canEditClasses`(개강 전에만 열기)는 이 판정보다 **더 좁으므로**
					그대로 두셔도 충돌하지 않는다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "반 삭제 성공(본문 없음)"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "오퍼레이터 권한이 없음"),
			@ApiResponse(responseCode = "404", description = "CLASSROOM_NOT_FOUND 반을 찾을 수 없거나 지정한 기수에 속하지 않음"),
			@ApiResponse(responseCode = "409", description = "CLASSROOM_NOT_DELETABLE 팀이 편성됐거나 리포트가 있는 반 — 무엇이 붙어 있는지는 message에 있다")
	})
	@PreAuthorize("hasRole('OPERATOR')")
	@DeleteMapping("/{classroomId}")
	public ResponseEntity<Void> deleteClassroom(
			@Parameter(description = "대상 반이 속한 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Parameter(description = "삭제할 반 ID. cohortId 소속이 아니면 404", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID classroomId,
			Authentication authentication
	) {
		UUID organizationId = extractOrganizationId(authentication);
		UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
		classroomService.deleteClassroom(cohortId, classroomId, organizationId, actorUserId);
		return ResponseEntity.noContent().build();
	}

	@Operation(
			operationId = "updateManagers",
			summary = "반 담당 매니저 변경 | ✅ 사용 가능",
			description = """
					반의 담당 매니저를 **전체 교체**한다. 부분 추가·삭제가 아니라 보낸 목록이 그대로 최종 상태가 된다 —
					기존 활성 배정을 모두 해제(사유 `MANUAL_UNASSIGN`)한 뒤 요청받은 매니저로 새 배정을 만든다.
					빈 배열을 보내면 전체 해제가 되어 담당자가 없는 반이 된다.

					**요청**
					- cohortId / classroomId (경로): 대상 반. classroomId가 그 기수 소속이 아니면 404
					- managerIds (필수): 최종 담당 매니저 ID 목록. 중복은 서버가 제거한다. 빈 배열 허용(전체 해제)

					**응답 (200)**
					- 변경 후의 반 정보(응답 필드는 "기수 반 목록 조회"의 classrooms[] 항목과 동일,
					  managers·managerAssignmentRequired가 갱신되어 표를 그대로 다시 그릴 수 있다)

					해제된 배정은 지워지지 않고 이력으로 남는다 — 과거 기수의 담당자를 추적할 수 있어야 하기 때문이다.

					**managerIds는 검증한다(25차 R3).** 이 기관의 매니저가 아닌 ID가 하나라도 있으면
					`404 MANAGER_NOT_FOUND`로 **전체를 거부**하며 어느 ID가 문제인지 메시지에 담는다.
					종전에는 검증이 없어 존재하지 않는 사용자 ID로도 배정 행이 만들어졌다 —
					매니저 쪽 같은 기능(`PUT …/managers/{managerId}/classrooms`)은 처음부터 막고
					있었으므로, 같은 일을 하는 두 경로의 계약이 서로 달랐다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "담당 매니저 변경 성공"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED managerIds가 누락됨"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "오퍼레이터 권한이 없음"),
			@ApiResponse(responseCode = "404", description = "CLASSROOM_NOT_FOUND 반을 찾을 수 없거나 지정한 기수에 속하지 않음 · MANAGER_NOT_FOUND 이 기관의 매니저가 아닌 ID가 포함됨(전체 거부, 25차 R3)")
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
			operationId = "assignTrainees",
			summary = "교육생 일괄 반 배정 | ✅ 사용 가능",
			description = """
					교육생 여러 명을 한 반으로 **옮긴다**(이동 배정). 대상자가 이미 다른 반에 있으면 그 배정을
					해제(사유 `MANUAL_MOVE`)한 뒤 새 반에 넣으므로, 교육생은 항상 기수 안에서 반 하나에만 속한다.

					**이동에 되돌리기를 먼저 부를 필요가 없다.** 이미 배정된 사람을 그대로 보내면 되고,
					같은 반으로 다시 보내도 된다(그 경우 배정 이력만 한 줄 새로 쌓인다). 25차 R7 전까지는
					이 자리에서 `409 DATA_INTEGRITY_VIOLATION`이 났는데, 해제 사유 값이 DB 제약에 없는
					값이어서 해제 자체가 실패한 것이었다 — 계약이 아니라 결함이었고 지금은 나지 않는다.

					**요청**
					- cohortId (경로): 대상 기수
					- classroomId (필수): 배정할 반 ID. 그 기수 소속이 아니면 404
					- traineeIds (필수, 1건 이상): 배정할 교육생의 사용자 ID(user_id) 목록. 중복은 서버가 제거한다

					**응답 (200)**
					- classroomId: 배정된 반
					- assignedTraineeIds: 실제 배정된 교육생 ID 목록(중복 제거 후)
					- assignedCount: 배정 인원 수

					전부 성공하거나 전부 실패한다. 요청한 교육생 중 한 명이라도 그 기수 소속이 아니면
					배정을 시작하지 않고 400으로 거절하며, 어느 ID가 문제인지 메시지에 담는다 —
					일부만 반영되면 어디까지 처리됐는지 화면에서 알 수 없기 때문이다.

					⚠️ 정원(capacity)을 초과해도 막지 않는다. 반 정원은 현재 표시용 값이다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "반 배정 성공"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED traineeIds가 비었음 · TRAINEE_NOT_IN_COHORT 기수에 속하지 않은 교육생이 포함됨(일부만 처리하지 않고 전체를 거부한다)"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "오퍼레이터 권한이 없음"),
			@ApiResponse(responseCode = "404", description = "CLASSROOM_NOT_FOUND 반을 찾을 수 없거나 지정한 기수에 속하지 않음")
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
			operationId = "rollbackAssignment",
			summary = "교육생 반 배정 되돌리기 | ✅ 사용 가능",
			description = """
					교육생의 현재 반 배정을 **해제만** 한다(사유 `IMMEDIATE_ROLLBACK`). `assignTrainees`(이동 배정)와
					달리 새 배정을 만들지 않으므로, 처리 후 해당 교육생은 어느 반에도 속하지 않는 상태가 된다.

					**요청**
					- cohortId (경로): 대상 기수
					- traineeIds (필수, 1건 이상): 되돌릴 교육생의 사용자 ID(user_id) 목록. 중복은 서버가 제거한다

					**응답 (200)**
					- rolledBackTraineeIds: 실제로 배정이 해제된 교육생 ID 목록(중복 제거 후)
					- rolledBackCount: 해제 인원 수

					전부 성공하거나 전부 실패한다. 요청한 교육생 중 한 명이라도 그 기수 소속이 아니거나
					현재 활성 배정이 없으면(이미 무소속이면) 처리를 시작하지 않고 400으로 거절한다 — 이미
					무소속인 사람을 "되돌리기"하는 건 의미 있는 동작이 아니기 때문이다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "반 배정 되돌리기 성공"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED traineeIds가 비었음 · TRAINEE_NOT_IN_COHORT 기수에 속하지 않은 교육생 · ASSIGNMENT_NOT_FOUND 되돌릴 활성 배정이 없는 교육생"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "오퍼레이터 권한이 없음"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND 기수를 찾을 수 없음(다른 기관의 기수 포함)")
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

	private UUID extractOrganizationId(Authentication authentication) {
		Object details = authentication.getDetails();
		if (!(details instanceof UUID organizationId)) {
			throw new ApiException(AcademicOperationsErrorCode.ORGANIZATION_CONTEXT_MISSING);
		}
		return organizationId;
	}
}