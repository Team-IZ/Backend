package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.member.application.MemberInvitationService;
import com.bigproject.backend.domain.member.application.TraineeCsvParser;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesRequest;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesResponse;
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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
}
