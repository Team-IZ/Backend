package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.member.application.MemberInvitationService;
import com.bigproject.backend.domain.member.application.TraineeCsvParser;
import com.bigproject.backend.domain.member.application.TraineeRosterService;
import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.TraineeRosterRepository;
import com.bigproject.backend.domain.member.domain.TraineeRosterSort;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesRequest;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesResponse;
import com.bigproject.backend.domain.member.presentation.dto.TraineeRosterResponse;
import com.bigproject.backend.domain.member.presentation.dto.UpdateTraineeStatusRequest;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

// 태그는 Member 도메인으로 합친다. 설명은 MemberController 쪽 @Tag가 대표로 싣는다.
@Tag(name = "Member")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RestController
@RequestMapping(value = "/cohorts/{cohortId}/trainees", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class TraineeController {
	private static final String REQUEST_ID_HEADER = "X-Request-Id";

	private final MemberInvitationService memberInvitationService;
	private final TraineeCsvParser traineeCsvParser;
	private final TraineeRosterService traineeRosterService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(
			operationId = "registerTraineesFromCsv",
			summary = "CSV 교육생 명단 등록 및 초대 | ✅ 사용 가능",
			description = """
					오퍼레이터가 CSV 파일을 올려 기수 교육생을 한 번에 등록하고 초대 메일을 보낸다.

					**요청** (multipart/form-data)
					- cohortId (경로): 교육생을 등록할 기수 ID
					- file (필수): UTF-8 CSV. **첫 행은 `이름,이메일` 헤더**이며 최대 1MB·1,000행
					- X-Request-Id (헤더, 선택): 일괄 등록 추적용 식별자. 생략하면 서버가 만든다

					**응답 (201)**
					- requestedCount: 받은 전체 행 수
					- registeredCount: 계정·명단·초대 원장 생성에 성공한 수
					- invitationSentCount: 초대 메일 발송까지 끝난 수
					- failures[]: 실패한 행만 담긴 목록
					  - row: **헤더를 포함한 실제 CSV 행 번호**(첫 데이터 행이 2)
					  - email: 그 행에 적힌 이메일
					  - status: 1=이메일 형식 오류, 2=요청 안에서 중복, 3=기관에 이미 있는 교육생 이메일

					**행별 부분 성공을 허용한다.** 한 행이 실패해도 나머지는 등록되므로, 화면은 201을 성공으로 처리하되
					`failures`가 비어 있는지 반드시 확인해야 한다 — 실패가 있어도 상태코드는 201이다.

					**메일이 나갔다고 계정이 활성화된 것은 아니다.** 교육생은 PENDING 상태로 만들어지고,
					본인이 초대 링크에서 `POST /auth/trainee-activation`을 마쳐야 활성 계정이 된다.
					"""
	)
	@PreAuthorize("hasRole('OPERATOR')")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "CSV 행별 등록·초대 처리 완료; 성공·실패 건수와 실패 행을 응답"),
			@ApiResponse(responseCode = "400", description = "CSV 파일·헤더·인코딩 또는 열 구성이 올바르지 않음"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 인증 사용자를 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "오퍼레이터 권한·계정 상태 또는 기관 범위가 허용되지 않음"),
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
			operationId = "registerTrainees",
			summary = "직접 입력 교육생 등록 및 초대 | ✅ 사용 가능",
			description = """
					CSV 업로드 대신 화면에서 이름·이메일을 직접 입력해 교육생을 등록한다.
					처리 규칙과 응답 형식은 CSV 등록(`POST /cohorts/{cohortId}/trainees`)과 완전히 같다.

					**요청** (application/json)
					- cohortId (경로): 교육생을 등록할 기수 ID
					- trainees (필수, 1건 이상): 등록할 교육생 목록
					  - name (필수, 최대 200자)
					  - email (필수): 형식·요청 내 중복·기존 계정을 행별로 검증한다
					- X-Request-Id (헤더, 선택): 일괄 등록 추적용 식별자

					**응답 (201)**
					- requestedCount / registeredCount / invitationSentCount
					- failures[]: row는 **1부터 시작하는 배열 순번**(CSV와 달리 헤더가 없다),
					  email, status(1=형식 오류, 2=요청 내 중복, 3=기관에 이미 있는 교육생 이메일)

					**행별 부분 성공을 허용한다.** 실패가 있어도 상태코드는 201이므로 `failures`를 확인해야 한다.

					**이름이 비어 있으면 행 단위 실패가 아니라 요청 전체가 400이다.** 이메일 오류는 failures로
					돌려주지만 이름 누락은 입력 화면에서 먼저 걸러야 할 값으로 보기 때문이다.

					등록된 교육생은 PENDING이며 `POST /auth/trainee-activation`을 거쳐야 활성화된다.
					"""
	)
	@PreAuthorize("hasRole('OPERATOR')")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "직접 입력 행별 등록·초대 처리 완료; 성공·실패 건수와 실패 행을 응답"),
			@ApiResponse(responseCode = "400", description = "교육생 목록·이름·이메일 값이 올바르지 않음"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 인증 사용자를 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "오퍼레이터 권한·계정 상태 또는 기관 범위가 허용되지 않음"),
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

	@Operation(
			operationId = "findTraineeRoster",
			summary = "기수 교육생 명단 조회 | ✅ 사용 가능",
			description = """
					운영 관리 `반·명단` 탭의 교육생 명단 표를 채운다. 소속 반·계정 상태로 필터링하고
					이름·이메일로 검색하며 페이지네이션한다. 조회 범위인 기관은 액세스 토큰에서 가져온다.

					**요청**
					- cohortId (경로): 조회할 기수 ID
					- classroomId (쿼리, 선택): 특정 반으로 좁힌다. unassignedOnly와 함께 지정하면 400
					- unassignedOnly (쿼리, 선택, 기본 false): true면 반 배정이 없는 교육생만 조회한다
					- accountStatus (쿼리, 선택): INVITED(초대 대기) / ACTIVE(활성) / INACTIVE(비활성). 생략하면 전체.
					  LOCKED는 이 화면에서 쓰지 않는 값이라 지정하면 400
					- query (쿼리, 선택): 이름·이메일 부분검색
					- sort (쿼리, 선택, 기본 NAME): NAME(이름순) / RECENT_ENROLLED(최근 등록순)
					- page (쿼리, 선택, 기본 0) / size (쿼리, 선택, 기본 20, 최대 100)

					**응답 (200)**
					- content[]: 명단 목록(이름·이메일·계정 상태·소속 반·등록일·중도 이탈일)
					- page / size / totalElements / totalPages: 필터 적용 후 페이지 정보
					- unassignedCount: 반 배정이 없는 교육생 수. **필터와 무관하게 기수 전체 기준**이며
					  화면 상단 `미배정 N` 배지에 그대로 쓴다

					**content[].classroomId·className은 반 배정이 없으면 둘 다 null이다.**
					**content[].leftAt은 중도 이탈(cohort_member.status=LEFT)한 경우에만 값이 있으며,
					화면의 `중도 이탈 {날짜}` 비고가 이 값이다.**
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "명단 조회 성공"),
			@ApiResponse(responseCode = "400", description = "classroomId와 unassignedOnly를 함께 지정했거나 page·size·accountStatus 값이 올바르지 않음"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 인증 사용자를 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "오퍼레이터·매니저 권한 또는 기관 범위가 허용되지 않음"),
			@ApiResponse(responseCode = "404", description = "조회할 기수를 찾을 수 없음(다른 기관의 기수 포함)")
	})
	@PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
	@GetMapping
	public ResponseEntity<TraineeRosterResponse> findTraineeRoster(
			@Parameter(description = "명단을 조회할 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Parameter(description = "특정 반으로 좁힌다. unassignedOnly와 함께 지정할 수 없다")
			@RequestParam(required = false) UUID classroomId,
			@Parameter(description = "true면 반 배정이 없는 교육생만 조회한다")
			@RequestParam(required = false, defaultValue = "false") boolean unassignedOnly,
			@Parameter(description = "계정 상태 필터. INVITED/ACTIVE/INACTIVE만 지원하며 생략하면 전체")
			@RequestParam(required = false) AccountStatus accountStatus,
			@Parameter(description = "이름·이메일 부분검색", example = "강건우")
			@RequestParam(required = false) String query,
			@Parameter(description = "정렬 기준", example = "NAME")
			@RequestParam(required = false, defaultValue = "NAME") TraineeRosterSort sort,
			@Parameter(description = "0부터 시작하는 페이지 번호", example = "0")
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@Parameter(description = "페이지당 개수(최대 100)", example = "20")
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
			Authentication authentication
	) {
		UUID organizationId = extractOrganizationId(authentication);

		TraineeRosterService.RosterResult result = traineeRosterService.findRoster(
				cohortId, organizationId, classroomId, unassignedOnly, accountStatus, query, sort,
				PageRequest.of(page, size));

		Page<TraineeRosterRepository.RosterRow> rosterPage = result.page();
		TraineeRosterResponse response = new TraineeRosterResponse(
				rosterPage.getContent().stream().map(TraineeRosterResponse.Trainee::from).toList(),
				rosterPage.getNumber(),
				rosterPage.getSize(),
				rosterPage.getTotalElements(),
				rosterPage.getTotalPages(),
				result.unassignedCount()
		);
		return ResponseEntity.ok(response);
	}

	@Operation(
			operationId = "updateTraineeStatus",
			summary = "교육생 계정 상태 변경 | ✅ 사용 가능",
			description = """
					명단 표 행별 액션의 `비활성`/`활성화` 버튼에 대응한다. 벌크 선택 바가 아니라
					**행 하나**를 대상으로 한다 — 목업의 벌크 선택 바에는 `반 배정`·`초대 재발송`만 있고
					상태 변경은 행별 버튼으로만 제공되기 때문이다.

					**요청**
					- cohortId (경로): 대상 기수 ID
					- traineeId (경로): 목록 응답의 `content[].traineeId`(사용자 ID)
					- status (필수): ACTIVE(활성화) 또는 INACTIVE(비활성화)만. 그 외는 400
					- reason (선택): 변경 사유(감사 로그용)

					**응답 (200)**
					- 변경 후의 명단 한 행(응답 필드는 "기수 교육생 명단 조회"의 content[] 항목과 동일)

					**초대 대기(INVITED) 상태인 교육생은 대상이 아니다** — 아직 계정이 활성화되지 않아
					정지·재활성 개념이 성립하지 않는다. 시도하면 409다.

					이미 같은 상태면 아무 것도 바꾸지 않고 현재 값을 그대로 돌려준다(멱등).
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "상태 변경 성공"),
			@ApiResponse(responseCode = "400", description = "ACTIVE/INACTIVE 외의 상태를 지정함"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 인증 사용자를 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "오퍼레이터 권한 또는 기관 범위가 허용되지 않음"),
			@ApiResponse(responseCode = "404", description = "교육생을 찾을 수 없음(다른 기수·다른 기관 포함)"),
			@ApiResponse(responseCode = "409", description = "초대 대기 상태인 교육생의 상태를 직접 변경하려 함")
	})
	@PreAuthorize("hasRole('OPERATOR')")
	@PatchMapping("/{traineeId}/status")
	public ResponseEntity<TraineeRosterResponse.Trainee> updateTraineeStatus(
			@Parameter(description = "대상 교육생이 속한 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Parameter(description = "상태를 바꿀 교육생의 사용자 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID traineeId,
			@Valid @RequestBody UpdateTraineeStatusRequest request,
			Authentication authentication
	) {
		if (!request.isMutableStatus()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "교육생 계정 상태는 활성 또는 비활성만 직접 설정할 수 있습니다.");
		}
		UUID organizationId = extractOrganizationId(authentication);
		UUID actorUserId = currentUserResolver.resolveCurrentMemberId();

		TraineeRosterRepository.RosterRow updated = traineeRosterService.updateStatus(
				cohortId, organizationId, traineeId, request.status(), request.reason(), actorUserId);
		return ResponseEntity.ok(TraineeRosterResponse.Trainee.from(updated));
	}

	private UUID extractOrganizationId(Authentication authentication) {
		Object details = authentication.getDetails();
		if (!(details instanceof UUID organizationId)) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
					"인증 정보에서 organizationId(UUID)를 확인할 수 없습니다.");
		}
		return organizationId;
	}
}
