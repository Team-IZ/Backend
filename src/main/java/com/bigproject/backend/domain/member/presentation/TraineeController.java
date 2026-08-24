package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.academicoperations.domain.AcademicOperationsErrorCode;
import com.bigproject.backend.domain.member.application.MemberInvitationService;
import com.bigproject.backend.domain.member.application.TraineeCsvParser;
import com.bigproject.backend.domain.member.application.TraineeRosterService;
import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.domain.member.domain.TraineeRosterRepository;
import com.bigproject.backend.domain.member.domain.TraineeRosterSort;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesRequest;
import com.bigproject.backend.domain.member.presentation.dto.PreviewTraineesResponse;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesResponse;
import com.bigproject.backend.domain.member.presentation.dto.ResendTraineeInvitationsRequest;
import com.bigproject.backend.domain.member.presentation.dto.ResendTraineeInvitationsResponse;
import com.bigproject.backend.domain.member.presentation.dto.TraineeRegistrationProgressResponse;
import com.bigproject.backend.domain.member.presentation.dto.TraineeRosterResponse;
import com.bigproject.backend.domain.member.presentation.dto.UpdateTraineeStatusRequest;
import com.bigproject.backend.global.exception.ApiException;
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
	private static final String MANAGER_ROLE = "ROLE_MANAGER";

	private final MemberInvitationService memberInvitationService;
	private final TraineeCsvParser traineeCsvParser;
	private final TraineeRosterService traineeRosterService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(
			operationId = "previewTraineesFromCsv",
			summary = "CSV 교육생 명단 사전 검증(드라이런) | ✅ 사용 가능",
			description = """
					**아무것도 만들지 않고** 무엇이 걸리는지만 돌려준다(9차 Q3-③).
					200명을 붙여 넣고 나서야 30명이 중복이라는 걸 알게 되는 것을 없앤다.

					**요청** (multipart/form-data) — 등록(`POST /cohorts/{cohortId}/trainees`)과 완전히 같다.
					- cohortId (경로) · file: 첫 행에 `이름`·`이메일` 열이 있는 CSV
					  (UTF-8·CP949 둘 다 되고 열 순서는 상관없다 — 등록 API와 같은 파서다, 25차 Q1)

					**응답 (200)** — 등록과 **다른 스키마**다(`PreviewTraineesResponse`, 31차 R2).
					종전에는 등록 응답을 그대로 돌려줬는데, `registeredCount`라는 이름이 「등록됐다」로 읽혀
					미리보기가 진짜 등록을 했는지 명단을 뒤지게 만들었다. **이 응답의 수는 전부 「그렇게 될 것」이다.**

					| 필드 | 뜻 |
					|---|---|
					| `requestedCount` | 검사한 행 수 |
					| `registrableCount` | **등록될 수 있는** 행 수(= requestedCount − failures.length) |
					| `failures[]` | 걸린 행. `row`·`email`·`status`는 등록과 같은 의미 |

					`invitationSentCount`·`batchRequestId`는 **없다** — 보낸 메일도 폴링할 잡도 없어
					각각 0·null로 고정돼 있던 자리다. 실패 목록(`Failure`)만 등록과 같은 형을 그대로 쓴다.

					## 판정은 등록과 **같은 규칙**이다

					행별 사유(1=형식 오류, 2=요청 내 중복, 3=기관에 이미 있는 이메일)를 등록 경로와
					**한 메서드**에서 판정하므로, 미리보기가 통과시킨 행을 등록이 거절하는 일이 없다.

					이름이 비어 있거나 200자를 넘으면 등록과 똑같이 **요청 전체가 400**이다 —
					미리보기에서만 통과시키면 미리보기의 뜻이 없어진다.

					⚠️ **미리보기가 통과했다고 등록이 반드시 성공하지는 않는다.** 두 호출 사이에 다른 운영자가
					같은 주소를 등록할 수 있다. 등록 응답의 `failures`는 그대로 확인해야 한다.

					💡 **형식 오류·기관 도메인 밖 주소는 화면이 그 자리에서 걸러도 된다**(`rules.parseRosterCsv`).
					이 API가 꼭 필요한 것은 **이미 등록된 이메일**이며, 명단 전량을 받아야 셀 수 있어 서버여야 한다.
					"""
	)
	@PreAuthorize("hasRole('OPERATOR')")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "사전 검증 완료; 등록될 수 있는 수(registrableCount)와 걸린 행을 응답(아무것도 만들지 않음)"),
			@ApiResponse(responseCode = "400", description = "CSV_FORMAT_INVALID CSV 파일·헤더·인코딩·열 구성 오류 · TRAINEE_NAME_INVALID 이름이 비었거나 200자 초과"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰 없음 · INVITER_NOT_FOUND 인증 사용자를 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "INVITE_ROLE_NOT_ALLOWED 오퍼레이터만 교육생을 초대할 수 있음 · INVITER_NOT_ACTIVE 활성 계정이 아님 · INVITE_CROSS_ORGANIZATION 다른 기관의 기수"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_INVITABLE 등록 가능한 기수를 찾을 수 없음")
	})
	@PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<PreviewTraineesResponse> previewTraineesFromCsv(
			@Parameter(description = "교육생을 등록할 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Parameter(description = "첫 행에 '이름'·'이메일' 열이 있는 CSV 파일(UTF-8 또는 CP949, 열 순서 무관)")
			@RequestPart("file") MultipartFile file,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		return ResponseEntity.ok(memberInvitationService.previewTrainees(
				cohortId,
				traineeCsvParser.parse(file),
				authentication.getName()
		));
	}

	@Operation(
			operationId = "previewTrainees",
			summary = "직접 입력 교육생 명단 사전 검증(드라이런) | ✅ 사용 가능",
			description = """
					CSV 대신 화면에서 직접 입력한 명단을 **등록하지 않고** 검사한다(9차 Q3-③).
					판정 규칙과 응답 형식은 CSV 사전 검증(`POST /cohorts/{cohortId}/trainees/preview`)과 완전히 같다.

					**요청** (application/json) — 등록(`POST …/trainees/invitations`)과 같은 본문이다.
					- trainees[] (필수, 1건 이상): name(필수, 최대 200자) · email

					**응답 (200)** — `PreviewTraineesResponse`. `registrableCount`는 **등록될 수 있는 수**이며
					아직 등록되지 않았다. `failures[].row`는 1부터 시작하는 배열 순번이다(CSV와 달리 헤더가 없다).
					"""
	)
	@PreAuthorize("hasRole('OPERATOR')")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "사전 검증 완료; 등록될 수 있는 수(registrableCount)와 걸린 행을 응답(아무것도 만들지 않음)"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED 교육생 목록이 비었음 · TRAINEE_NAME_INVALID 이름이 비었거나 200자 초과"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰 없음 · INVITER_NOT_FOUND 인증 사용자를 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "INVITE_ROLE_NOT_ALLOWED 오퍼레이터만 교육생을 초대할 수 있음 · INVITER_NOT_ACTIVE 활성 계정이 아님 · INVITE_CROSS_ORGANIZATION 다른 기관의 기수"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_INVITABLE 등록 가능한 기수를 찾을 수 없음")
	})
	@PostMapping(path = "/invitations/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<PreviewTraineesResponse> previewTrainees(
			@Parameter(description = "교육생을 등록할 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Valid @RequestBody RegisterTraineesRequest request,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		return ResponseEntity.ok(memberInvitationService.previewTrainees(
				cohortId,
				request,
				authentication.getName()
		));
	}

	@Operation(
			operationId = "registerTraineesFromCsv",
			summary = "CSV 교육생 명단 등록 및 초대 | ✅ 사용 가능",
			description = """
					오퍼레이터가 CSV 파일을 올려 기수 교육생을 한 번에 등록하고 초대 메일 발송을 예약한다.

					**요청** (multipart/form-data)
					- cohortId (경로): 교육생을 등록할 기수 ID
					- file (필수): CSV. **첫 행에 `이름`·`이메일` 열**이 있어야 하며 최대 1MB·1,000행

					## 받는 파일 범위를 넓혔다 (25차 Q1)

					| | 종전 | 지금 |
					|---|---|---|
					| 인코딩 | UTF-8만 | **UTF-8 · CP949** — 윈도우 엑셀의 「CSV(쉼표로 분리)」 기본 저장이 그대로 올라간다 |
					| 열 | `이름,이메일` 두 개, 그 순서 | **머리글 이름으로 찾는다** — 순서가 달라도, `번호`·`소속` 같은 열이 섞여 있어도 된다 |

					모르는 열은 읽지 않는다. `이름`·`이메일` 중 하나라도 머리글에 없으면 400
					`CSV_FORMAT_INVALID`이고, 어느 열이 없는지 `message`에 담는다.

					**기존 파일은 그대로 통과한다** — UTF-8 `이름,이메일`은 넓힌 규칙의 부분집합이다.
					프런트가 인코딩·열을 미리 정규화할 필요가 없어졌다(프론트 제안 「가」는 하지 않아도 된다).

					⚠️ **`.xlsx`는 받지 않는다.** 파싱 라이브러리가 통째로 하나 더 붙는데, 엑셀 파일은
					서식·이미지 때문에 커지기 쉬워 앞단 Lambda의 6MB(base64로 부풀어 실질 4.5MB) 상한에
					먼저 걸린다 — 라이브러리를 넣고도 "큰 파일은 안 된다"가 남는다. 엑셀에서
					「CSV로 저장」 한 번이면 위 완화로 그대로 올라간다.

					## 🔴 등록은 202이고 메일은 그 뒤에 나간다

					**응답이 돌아온 시점에 메일은 한 통도 나가지 않았다.** 계정·초대 원장까지만 만들고 응답하며,
					발송은 서버가 이어서 진행한다. 900명 기준 발송에만 3분 안팎이 걸려 응답 안에서 끝낼 수 없기 때문이다
					(예전에는 그 때문에 화면에 502가 뜨는데 뒤에서는 등록이 계속되는 상태가 됐다).

					그래서 `invitationSentCount`는 **이 응답에서 항상 0**이다. 실제로 나간 수는
					`GET /cohorts/{cohortId}/trainees/registrations/{batchRequestId}`를 2~3초 간격으로 폴링해 확인한다.

					**응답 (202)**
					- requestedCount: 받은 전체 행 수
					- registeredCount: 계정·명단·초대 원장 생성에 성공한 수
					- invitationSentCount: **항상 0** — 위 설명 참고
					- batchRequestId: **서버가 만드는** 진행률 폴링 식별자. **등록된 행이 0건이면 null**이며 그때는 폴링할 것이 없다
					- failures[]: 실패한 행만 담긴 목록
					  - row: **헤더를 포함한 실제 CSV 행 번호**(첫 데이터 행이 2)
					  - email: 그 행에 적힌 이메일
					  - status: 1=이메일 형식 오류, 2=요청 안에서 중복, 3=기관에 이미 있는 교육생 이메일

					**행별 부분 성공을 허용한다.** 한 행이 실패해도 나머지는 등록되므로, 화면은 202를 성공으로 처리하되
					`failures`가 비어 있는지 반드시 확인해야 한다 — 실패가 있어도 상태코드는 202다.

					⚠️ **메일 발송 실패는 `failures`에 들어가지 않는다.** `failures`는 **등록 자체가 되지 않은 행**
					(형식 오류·중복·기존 이메일)만 담으며, 이 응답 시점에는 아직 발송이 시작되지도 않았다.
					발송 실패는 폴링 응답의 `mailFailedCount`로 드러난다.

					발송되지 않은 초대는 원장에 `DELIVERY_FAILED`로 남아 명단 조회 응답의
					`pendingInvitationTokenId`가 채워지므로, 화면은 그 행의 **[초대 재발송]** 버튼으로 복구시키면 된다.

					**메일이 나갔다고 계정이 활성화된 것은 아니다.** 교육생은 PENDING 상태로 만들어지고,
					본인이 초대 링크에서 `POST /auth/trainee-activation`을 마쳐야 활성 계정이 된다.
					"""
	)
	@PreAuthorize("hasRole('OPERATOR')")
	@ApiResponses({
			@ApiResponse(responseCode = "202", description = "등록 완료·발송 예약됨; 성공·실패 건수와 폴링용 batchRequestId를 응답"),
			@ApiResponse(responseCode = "400", description = "CSV_FORMAT_INVALID CSV 파일·헤더·인코딩·열 구성 오류. 어느 행이 왜 틀렸는지는 message에 있다"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰 없음 · INVITER_NOT_FOUND 인증 사용자를 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "INVITE_ROLE_NOT_ALLOWED 오퍼레이터만 교육생을 초대할 수 있음 · INVITER_NOT_ACTIVE 활성 계정이 아님 · INVITE_CROSS_ORGANIZATION 다른 기관의 기수"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_INVITABLE 등록 가능한 기수를 찾을 수 없음")
	})
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<RegisterTraineesResponse> registerTraineesFromCsv(
			@Parameter(description = "교육생을 등록할 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Parameter(description = "첫 행에 '이름'·'이메일' 열이 있는 CSV 파일(UTF-8 또는 CP949, 열 순서 무관)")
			@RequestPart("file") MultipartFile file,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(memberInvitationService.inviteTraineesFromCsv(
				cohortId,
				traineeCsvParser.parse(file),
				authentication.getName()
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

					## 🔴 CSV 등록과 똑같이 202이고 메일은 그 뒤에 나간다

					건수가 적어도 **경로를 갈라 두지 않는다** — 화면이 응답 두 벌을 처리하는 분기를 갖게 되고,
					같은 등록인데 입력 방식에 따라 계약이 달라진다. `invitationSentCount`는 여기서도 **항상 0**이며
					실제 발송 수는 `batchRequestId`로 폴링해 확인한다.

					**응답 (202)**
					- requestedCount / registeredCount / invitationSentCount(항상 0)
					- batchRequestId: **서버가 만드는** 진행률 폴링 식별자. 등록된 행이 0건이면 null
					- failures[]: row는 **1부터 시작하는 배열 순번**(CSV와 달리 헤더가 없다),
					  email, status(1=형식 오류, 2=요청 내 중복, 3=기관에 이미 있는 교육생 이메일)

					**행별 부분 성공을 허용한다.** 실패가 있어도 상태코드는 202이므로 `failures`를 확인해야 한다.

					⚠️ **메일 발송 실패는 `failures`에 들어가지 않는다.** CSV 등록과 같은 규칙이다 —
					발송 실패 수는 폴링 응답의 `mailFailedCount`이며, 그 행들은 명단의 **[초대 재발송]** 으로 복구한다.

					**이름이 비어 있으면 행 단위 실패가 아니라 요청 전체가 400이다.** 이메일 오류는 failures로
					돌려주지만 이름 누락은 입력 화면에서 먼저 걸러야 할 값으로 보기 때문이다.

					등록된 교육생은 PENDING이며 `POST /auth/trainee-activation`을 거쳐야 활성화된다.
					"""
	)
	@PreAuthorize("hasRole('OPERATOR')")
	@ApiResponses({
			@ApiResponse(responseCode = "202", description = "등록 완료·발송 예약됨; 성공·실패 건수와 폴링용 batchRequestId를 응답"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED 교육생 목록이 비었음 · TRAINEE_NAME_INVALID 이름이 비었거나 200자 초과 · EMAIL_FORMAT_INVALID 이메일 형식 오류"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰 없음 · INVITER_NOT_FOUND 인증 사용자를 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "INVITE_ROLE_NOT_ALLOWED 오퍼레이터만 교육생을 초대할 수 있음 · INVITER_NOT_ACTIVE 활성 계정이 아님 · INVITE_CROSS_ORGANIZATION 다른 기관의 기수"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_INVITABLE 등록 가능한 기수를 찾을 수 없음")
	})
	@PostMapping(path = "/invitations", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<RegisterTraineesResponse> registerTrainees(
			@Parameter(description = "교육생을 등록할 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Valid @RequestBody RegisterTraineesRequest request,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(memberInvitationService.inviteTrainees(
				cohortId,
				request,
				authentication.getName()
		));
	}

	@Operation(
			operationId = "findTraineeRegistrationProgress",
			summary = "교육생 일괄 등록 진행률 조회 | ✅ 사용 가능",
			description = """
					등록(`POST …/trainees` · `POST …/trainees/invitations`)이 202로 돌려준 `batchRequestId`의
					**초대 메일 발송 진행률**을 답한다. 2~3초 간격으로 폴링하다가 `status`가 `RUNNING`이 아니면 멈춘다.

					## 요청

					| 파라미터 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `cohortId` | 필수 | UUID | 경로. 등록을 실행한 기수 |
					| `batchRequestId` | 필수 | string | 경로. 등록 응답의 `batchRequestId` |

					## 응답 (200)

					| 필드 | 설명 |
					|---|---|
					| `batchRequestId` | 조회에 쓴 식별자 |
					| `registeredCount` | 이 등록으로 만들어진 초대 수. 등록 응답의 같은 이름 값과 일치한다 |
					| `invitationSentCount` | **실제로 메일이 나간 수.** 이미 가입까지 마친 교육생도 포함한다 |
					| `mailFailedCount` | 메일이 나가지 못한 수. 명단의 **[초대 재발송]** 으로 복구한다 |
					| `mailPendingCount` | 아직 발송을 기다리는 수 |
					| `status` | `RUNNING`(발송 중) · `PARTIAL`(발송 끝, 실패 있음) · `SUCCEEDED`(전부 발송) |

					`registeredCount = invitationSentCount + mailFailedCount + mailPendingCount`가 성립한다
					(초대가 취소·만료되면 그만큼 어긋날 수 있다).

					💡 **잡을 따로 저장하지 않는다.** 초대 원장을 그 자리에서 집계해 답하므로, 인스턴스가 여러 대여도
					어느 쪽이 폴링을 받든 같은 답이 나온다. 진행률이 사라지거나 행 상태와 어긋날 여지가 없다.

					⚠️ **등록 응답의 `batchRequestId`가 `null`이면 폴링하지 않는다.** 명단이 전부 사전 판정에 걸려
					초대가 하나도 만들어지지 않은 경우이며, 조회하면 404다.

					💡 **이 값은 서버가 만든다.** 화면은 등록 응답에서 받은 값을 그대로 넣기만 하면 되고,
					만들어 넣을 수 없다 — 잡 식별자를 클라이언트가 정하면 같은 값으로 두 번 등록했을 때
					두 등록의 진행률이 하나로 합쳐진다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "진행률 조회 성공"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 만료됨"),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED 오퍼레이터 권한이 아님"),
			@ApiResponse(responseCode = "404", description = "REGISTRATION_BATCH_NOT_FOUND 그 기수·기관에 그 식별자로 만들어진 초대가 없음"),
			@ApiResponse(responseCode = "500", description = "ORGANIZATION_CONTEXT_MISSING 인증 정보에서 기관을 확인할 수 없음")
	})
	@PreAuthorize("hasRole('OPERATOR')")
	@GetMapping("/registrations/{batchRequestId}")
	public ResponseEntity<TraineeRegistrationProgressResponse> findTraineeRegistrationProgress(
			@Parameter(description = "등록을 실행한 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Parameter(description = "등록 응답의 batchRequestId", example = "trainee-batch-001")
			@PathVariable String batchRequestId,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		UUID organizationId = extractOrganizationId(authentication);
		return ResponseEntity.ok(TraineeRegistrationProgressResponse.of(
				batchRequestId,
				memberInvitationService.findRegistrationProgress(cohortId, organizationId, batchRequestId)
		));
	}

	@Operation(
			operationId = "findTraineeRoster",
			summary = "기수 교육생 명단 조회 | ✅ 사용 가능",
			description = """
					운영 관리 `반·명단` 탭의 교육생 명단 표를 채운다. 소속 반·계정 상태 필터, 이름·이메일 검색,
					정렬, 페이지네이션을 **모두 서버가 처리**하므로 화면은 파라미터만 넘기면 된다.
					조회 범위인 기관은 액세스 토큰에서 가져온다.

					## 요청 (경로 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					| --- | --- | --- | --- |
					| `cohortId` | 필수 | UUID | 명단을 조회할 기수 |

					## 요청 (쿼리 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					| --- | --- | --- | --- |
					| `classroomId` | 선택 | UUID | 특정 반으로 좁힌다. 생략하면 (매니저는 담당 반, 오퍼레이터는 기수) 전체. `unassignedOnly`와 함께 지정하면 400 |
					| `unassignedOnly` | 선택 | boolean | 반 배정이 없는 교육생만. 기본 `false`. **오퍼레이터 전용** — 매니저가 `true`로 보내면 400 |
					| `accountStatus` | 선택 | enum | `INVITED`(초대 대기) · `ACTIVE`(활성) · `INACTIVE`(비활성). 비우면 전체(화면의 `계정 · 전체`) |
					| `query` | 선택 | string | 이름·이메일 부분검색. 비우면 전체 |
					| `sort` | 선택 | enum | `NAME`(이름순, 기본) · `RECENT_ENROLLED`(최근 등록순, **등록일 없는 사람은 맨 뒤**) · `RISK`(위험순) · `EXCELLENCE`(우수순) |
					| `assessmentRoundId` | 선택 | UUID | **이 화면의 `회차 · 미프 N차` 필터.** 도달 단계·2단 이하·위험 배지·우수 누적 같은 회차 지표를 이 회차 기준으로 채운다. **생략하면 서버가 「이번 회차」를 고른다**(아래 표) |
					| `page` | 선택 | int | 0부터 시작. 기본 `0` |
					| `size` | 선택 | int | 페이지당 개수. 기본 `20`, 최대 `100` |

					💡 **`accountStatus`는 세 값뿐이다.** `LOCKED`는 9차 Q3-②로 `AccountStatus`에서 제거했다 —
					`ck_app_user_status`가 `PENDING`·`ACTIVE`·`INACTIVE`만 허용해 실제로 올 수 없던 값이다.

					💡 **`RECENT_ENROLLED`는 등록일 있는 사람 → 없는 사람 순이다(25차 R5).** `joinedAt`이
					`null`인 초대 수락 전 교육생은 **맨 뒤**로 간다(`NULLS LAST`). 종전에는 초대 시각으로
					같이 정렬해서, 「최근 등록순」을 골랐는데 등록일 열이 `—`인 사람들이 맨 위에 쌓였다.
					미등록자끼리는 최근에 초대한 사람이 앞이다.

					⚠️ **`classroomId`와 `unassignedOnly`는 함께 못 쓴다.** 화면에서 `반 · 전체 / 미배정 / A반…`이
					단일 드롭다운이라 동시에 지정될 일이 없고, 들어오면 400으로 막는다.

					💡 **`assessmentRoundId`를 생략하면 서버가 「이번 회차」를 골라 답한다.** 화면이 첫 진입에
					이미 `회차 · 미프 3차`를 고른 상태로 떠야 하는데, 그 값을 알려면 회차 목록을 먼저 받아야 해서
					호출이 두 번이 된다. 응답의 `rounds[]`(드롭다운 선택지)와 `assessmentRoundId`(**실제로 쓴 회차**)를
					함께 돌려주므로 한 번의 호출로 표와 드롭다운을 같이 그릴 수 있다.

					판정은 `GET /cohorts/{cohortId}/projects/current`와 **완전히 같은 규칙**이다(15차 R1) —
					서버가 규칙을 한 벌만 갖는다.

					| 순서 | 고르는 것 |
					| --- | --- |
					| ① | `RUNNING`인 프로젝트. 여럿이면 **가장 늦게 시작한** 것 |
					| ② | 없으면 **가장 이른 `PLANNED`** — 다음에 열릴 회차가 지금의 관심사다 |
					| ③ | 그것도 없으면 **마지막 프로젝트**(전부 `CLOSED`인 기수) |

					⚠️ **`rounds[]`의 마지막 원소가 기본값이라고 가정하지 마라.** 미프 2차가 진행 중이고 3차가
					아직 안 열렸으면 기본값은 **2차**다. 드롭다운의 선택 상태는 반드시 응답의
					`assessmentRoundId`에 맞춰야 대시보드의 `이번 회차`와 같은 차수를 가리킨다.

					③이 `CLOSED`를 돌려주므로 **화면은 "진행 중"이라고 단정하면 안 된다.**
					이번 회차 프로젝트에 회차가 아직 없으면 목록의 마지막 회차로 물러선다 — 아무것도 고르지
					못하면 지표 칸이 통째로 비기 때문이다.

					⚠️ **`assessmentRoundId`는 회차 지표만 바꾸고, 명단에 어느 교육생이 나오는지는 안 바꾼다.**
					회차를 고르면 그 회차의 도달·위험·우수 지표로 화면이 다시 그려지지만, 행 자체(누가 명단에
					있는지)는 기수 소속 기준 그대로다. 예를 들어 회차 시작 전(팀 미배정 시점)에도 프로필 행은
					남아 있고 도달·배지 칸만 비어 있다 — 회차가 명단의 **필터**가 아니라 **지표의 기준 시점**이기
					때문이다.

					⚠️ **회차 지표는 매니저에게만 채워진다.** 원천인 `manager_trainee_roster_view`가
					`manager_assignment` 기반인데 테이블정의서가 *'기수 전체 배정과 오퍼레이터 배정은 만들지
					않는다'*고 못박아, 오퍼레이터가 부르면 회차를 지정하든 말든 아래 회차 지표 열이 전부 `null`이다.
					오퍼레이터 화면(OP-06)은 이 열들을 그리지 않으므로 문제되지 않는다.

					⚠️ **`sort=RISK`·`EXCELLENCE`는 기준 회차가 있어야 한다.** 보통은 위처럼 서버가 최근 회차를
					골라 주므로 그냥 쓰면 되지만, **기수에 회차가 하나도 없으면**(= `rounds[]`가 빈 배열) 고를 것이
					없어 `ROSTER_ASSESSMENT_ROUND_REQUIRED`(400)로 막는다.

					⚠️ **매니저는 `unassignedOnly`를 쓸 수 없다.** 매니저 명단은 담당 반으로 좁혀져 있어 반이 없는
					교육생은 애초에 들어오지 않으므로 결과가 항상 빈다. 조용히 빈 표를 주면 화면이 데이터가 없다고
					오해하므로 `ROSTER_UNASSIGNED_FILTER_NOT_ALLOWED`(400)로 거절한다 — 이 필터는 오퍼레이터
					화면(OP-06)의 `소속 반 · 미배정` 전용이다. 같은 이유로 `unassignedCount`도 매니저에게는 늘 `0`이다.

					## 응답 (200)

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `content[]` | array | 명단 목록. 각 항목 구조는 아래 |
					| `page` | int | 현재 페이지(0부터) |
					| `size` | int | 페이지당 개수 |
					| `totalElements` | long | **필터 적용 후** 전체 건수 |
					| `totalPages` | int | 전체 페이지 수 |
					| `unassignedCount` | int | 반 배정이 없는 교육생 수. 화면 상단 `미배정 N` 배지. **매니저는 늘 `0`** |
					| `cohortTotal` | int | 기수 전체 교육생 수. 화면 상단 `명단 393명` |
					| `activeCount`·`invitedCount`·`inactiveCount` | int | 계정 상태별 인원. 화면 머리글의 `활성 24 · 초대 대기 1 · 비활성 1`. 셋을 더하면 `cohortTotal`이다(30차 R7) |
					| `rounds[]` | array | **`회차 · 미프 N차` 드롭다운 선택지.** 차수 오름차순이라 마지막이 가장 최근 |
					| `assessmentRoundId` | UUID? | **실제로 조회에 쓴 회차.** 드롭다운의 선택 상태를 이 값에 맞춘다. 회차가 없으면 `null` |

					### rounds[] 각 항목 — 회차 드롭다운

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `assessmentRoundId` | UUID | 회차 ID. 요청의 `assessmentRoundId`에 그대로 넣는 값 |
					| `roundNo` | int | 프로젝트 안의 회차 번호. 미니프로젝트는 **늘 1**이라 차수 표기에 쓸 수 없다 |
					| `cohortRoundNo` | int | **기수 안의 회차 순번.** 화면의 `미프 3차`에서 3이 이 값 |
					| `roundName` | string | 회차 이름 |
					| `projectId` | UUID | 회차가 속한 프로젝트 ID |
					| `projectName` | string | 회차가 속한 프로젝트 이름 |

					💡 **기수 단위 회차 목록 API가 따로 없어 명단과 같은 응답에 싣는다.** 없으면 화면이
					`GET /cohorts/{cohortId}/projects` 뒤에 프로젝트마다 `/rounds`를 다시 부르는 N+1이 된다.

					⚠️ **차수는 `cohortRoundNo`이지 `roundNo`가 아니다.** `roundNo`는 `(project_id, round_no)`가
					UNIQUE라 프로젝트마다 1부터 다시 시작하는데, 미니프로젝트는 프로젝트당 회차가 1건뿐이라
					전부 1이 되어 `미프 1차·2차·3차`를 구분하지 못한다.

					### content[] 각 항목

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `traineeId` | UUID | 교육생 사용자 ID. 상태 변경 시 경로에 쓴다 |
					| `name` | string | 이름 |
					| `email` | string | 이메일 |
					| `status` | enum | `INVITED` · `ACTIVE` · `INACTIVE` |
					| `classroomId` | UUID? | 현재 소속 반. 배정이 없으면 `null` |
					| `className` | string? | 현재 소속 반 이름. 배정이 없으면 `null` |
					| `joinedAt` | date-time | 기수 등록일 |
					| `leftAt` | date-time? | 중도 이탈일. 이탈하지 않았으면 `null` |
					| `inactivatedReasonCode` | enum? | 비활성화 사유 코드. 활성이면 `null` |
					| `inactivatedReason` | string? | 비활성화 상세 사유. **INACTIVE여도 `null`일 수 있다** |
					| `inactivatedById` | UUID? | 비활성화한 사용자 ID. 활성이면 `null` |
					| `inactivatedByName` | string? | 비활성화한 사용자 이름. 화면 표시용 |
					| `inactivatedAt` | date-time? | 비활성화 시각. 활성이면 `null` |
					| `pendingInvitationTokenId` | UUID? | 아직 수락·취소되지 않은 초대 토큰(11차 R2). `null`이 아닐 때만 재발송 버튼(`POST /cohorts/{cohortId}/trainees/invitations/resend`)을 켠다. 이미 활성화됐거나 초대가 취소됐으면 `null` |
					| `invitationDeliveryFailed` | boolean | 그 초대의 **메일이 나가지 못했는지**. `true`면 `초대 메일이 나가지 않았습니다` 안내를 띄운다 |

					#### 여기부터는 회차 지표다 — **매니저에게만** 채워지고 오퍼레이터는 전부 `null`이다

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `assessmentRoundId` | UUID? | 지표를 계산한 평가 회차. 응답 최상위의 `assessmentRoundId`와 같은 값 |
					| `attemptId` | UUID? | 그 회차의 응시 시도 ID. 아직 응시하지 않았으면 `null` |
					| `roundResultStatus` | enum? | 응시 시도 상태. `NOT_STARTED`(미시작) · `SUBMITTED` · `ANALYZING` · `SESSION_READY` · `SESSION_IN_PROGRESS` · `COMPLETED`(완료) · `FAILED` · `EXPIRED` |
					| `conceptResultItems` | string? | 문항별 결과의 JSON 배열(문자열로 직렬화됨). 각 항목은 `problemId`·`problemNo`·`conceptId`·`generationStatus`·`reachLevel`(0~4단, 미생성·무응답이면 `null`)을 가진다 |
					| `expectedConceptCount` | int? | `저단계 개수`의 분모. 실제로 생성된(`generationStatus='GENERATED'`) 문항 수이며 사람마다 다르다 |
					| `lowStageConceptCount` | int? | 도달 단계 **2단 미만(0~1단)** 인 문항 수. 응답한 문항이 하나도 없으면 `null`(화면의 `—`) |
					| `excellentOccurrenceCount` | int? | 이 교육생이 우수로 발견된 누적 횟수 |
					| `excellentAssessmentSequenceNos` | int[] | 우수로 발견된 프로젝트 차수(`analysis_sequence_no`) 전부. **조회 회차를 포함**하므로 이 배열에 조회 차수가 있으면 이번 회차도 우수다. 최신 차수부터 내림차순, 근거 없으면 빈 배열 |
					| `matchedRiskTypeCodes` | string[]? | 이번 회차에 걸린 위험 유형 코드 **배열**. `STAGE_DECLINE`(단계 하락) · `PERSISTENT_LOW`(지속 저점) · `INVALID_ATTEMPT`(무효 응시) · `CONTRIBUTION_UNDERSTANDING_GAP`(기여·이해도 괴리) · `LOW_PARTICIPATION`(저기여) 중 동시에 여러 개가 걸릴 수 있다. 해소(`RESOLVED`)된 사유는 들어오지 않는다. **`null`(지표 없음)과 `[]`(위험 없음)은 뜻이 다르다.** 30차 R6까지는 `"{}"` 같은 PostgreSQL 배열 리터럴 문자열이었다 |
					| `roundPrimaryStatusCode` | enum? | 배지 한 칸에 넣을 **단일** 코드. 1층 응시상태(`NOT_ATTENDED` 검증 세션 못 함 → `SESSION_INCOMPLETE` 응시 중단 → `INVALID_ATTEMPT` 무효 응시)가 있으면 2층 위험 유형(`LOW_PARTICIPATION` → `CONTRIBUTION_UNDERSTANDING_GAP` → `STAGE_DECLINE` → `PERSISTENT_LOW`)은 보지 않는다. 걸린 것이 없으면 `null`(정상). 중도 이탈은 여기 들어오지 않는다 — 계정 상태의 비활성화 사유로 이미 드러난다 |
					| `notAttendedReasonCode` | enum? | 🆕 `roundPrimaryStatusCode`가 `NOT_ATTENDED`일 때만. `NO_SHOW`(볼 수 있었는데 안 봄) · `NOT_SUBMITTED`(팀 미제출) · `ANALYSIS_FAILED`(분석 실패). **독촉 대상은 `NO_SHOW`뿐**이며 나머지 둘은 응시할 문항 자체가 없었다 |
					| `roundTerminalAt` | date-time? | `roundPrimaryStatusCode`가 `NOT_ATTENDED`·`SESSION_INCOMPLETE`일 때만 값이 있는 시각. 화면이 `우수 누적` 칸에 정상 결과 대신 `세션 중단 · 07-14`처럼 사유·일자를 그릴 때 쓴다 |

					🆕 **`roundPrimaryStatusCode = NOT_ATTENDED`의 범위가 넓어졌다.** 종전에는 `terminal_reason_code`가 `NOT_ATTENDED`인 경우만 봐서, **팀이 제출을 안 해 응시조차 못 한 학생이 배지 없이 「정상」으로 보였다.** 이제 팀 미제출·코드 분석 실패도 이 배지가 잡고, 원인은 `notAttendedReasonCode`로 가른다. 결과 탭(`GET /projects/{projectId}/evaluations`)의 `resultStatus`·`notAttendedReason`과 같은 값 집합이다.
					| `rowAggregationStatus` | string? | 이 행의 지표 집계 상태. 현재는 항상 `COMPLETE`다 |

					#### inactivatedReasonCode 값

					| 값 | 설명 |
					| --- | --- |
					| `RESIGNED` | 퇴사 |
					| `ADMIN_SUSPENDED` | 운영자 조치. 이 화면의 `비활성` 버튼이 넣는 값 |
					| `CONTRACT_ENDED` | 계약 종료 |
					| `SECURITY_ACTION` | 보안 조치 |
					| `OTHER` | 기타 |

					⚠️ **`unassignedCount`·`cohortTotal`·계정 상태별 3종은 필터와 무관한 기수 전체 기준**이라 `totalElements`와 다르다.
					검색 결과가 없을 때의 `7기 393명에서 찾았습니다`도 `cohortTotal`이며, 두 값이 같은 모집단이라
					`393명 중 미배정 12`가 그대로 성립한다.

					⚠️ **`classroomId`·`className`은 둘 다 있거나 둘 다 `null`이다.**

					⚠️ **`leftAt`은 중도 이탈(`cohort_member.status=LEFT`)한 경우에만 값이 있다.**
					화면의 `중도 이탈 {날짜}` 비고가 이 값이다.

					💡 **비활성 교육생은 `inactivatedReasonCode`·`inactivatedById`·`inactivatedAt`이 반드시 있다.**
					`ck_app_user_status_3`이 INACTIVE인 행에 이 셋을 NOT NULL로 강제하기 때문이다. 다만 상세 사유
					(`inactivatedReason`)는 상태 변경 요청에서 생략할 수 있어 `null`일 수 있다.

					💡 **`inactivatedByName`은 `inactivatedById`가 가리키는 계정의 이름이다.** 화면은 이 값을
					그대로 쓰면 되고 ID로 다시 조회할 필요가 없다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "명단 조회 성공"),
			@ApiResponse(responseCode = "400", description = "ROSTER_FILTER_CONFLICT classroomId와 unassignedOnly를 함께 지정함 · ROSTER_UNASSIGNED_FILTER_NOT_ALLOWED 매니저가 unassignedOnly를 지정함(오퍼레이터 전용) · ROSTER_ASSESSMENT_ROUND_REQUIRED sort가 RISK·EXCELLENCE인데 기수에 회차가 하나도 없음 · VALIDATION_FAILED accountStatus·sort에 없는 값을 지정했거나 page·size 값이 올바르지 않음"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 만료됨"),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED 오퍼레이터·매니저 권한이 아님"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND 조회할 기수를 찾을 수 없음. 다른 기관의 기수도 존재를 알리지 않고 여기로 묶는다"),
			@ApiResponse(responseCode = "500", description = "ORGANIZATION_CONTEXT_MISSING 인증 정보에서 기관을 확인할 수 없음")
	})
	@PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
	@GetMapping
	public ResponseEntity<TraineeRosterResponse> findTraineeRoster(
			@Parameter(description = "명단을 조회할 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Parameter(description = "특정 반으로 좁힌다. unassignedOnly와 함께 지정할 수 없다")
			@RequestParam(required = false) UUID classroomId,
			@Parameter(description = "true면 반 배정이 없는 교육생만 조회한다. 오퍼레이터 전용이며 매니저가 지정하면 400")
			@RequestParam(required = false, defaultValue = "false") boolean unassignedOnly,
			@Parameter(description = "계정 상태 필터. INVITED/ACTIVE/INACTIVE만 지원하며 생략하면 전체")
			@RequestParam(required = false) AccountStatus accountStatus,
			@Parameter(description = "이름·이메일 부분검색", example = "강건우")
			@RequestParam(required = false) String query,
			@Parameter(description = "정렬 기준", example = "NAME")
			@RequestParam(required = false, defaultValue = "NAME") TraineeRosterSort sort,
			@Parameter(description = "회차 지표를 채울 평가 회차 ID. 생략하면 가장 최근 회차를 서버가 고르며, 실제로 쓴 값은 응답의 assessmentRoundId에 담긴다")
			@RequestParam(required = false) UUID assessmentRoundId,
			@Parameter(description = "0부터 시작하는 페이지 번호", example = "0")
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@Parameter(description = "페이지당 개수(최대 100)", example = "20")
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
			Authentication authentication
	) {
		UUID organizationId = extractOrganizationId(authentication);

		// 매니저는 담당 반만 본다. 오퍼레이터는 기수 전체를 보므로 스코프를 걸지 않는다 —
		// 이 엔드포인트는 두 역할이 함께 쓰므로 권한 애너테이션만으로는 갈라지지 않는다.
		boolean manager = authentication.getAuthorities().stream()
				.anyMatch(authority -> MANAGER_ROLE.equals(authority.getAuthority()));

		// 미배정 필터는 오퍼레이터 전용이다. 매니저 목록은 담당 반으로 좁혀져 있어 반이 없는
		// 교육생은 애초에 들어오지 않으므로, 허용하면 조용히 빈 목록을 돌려주게 된다 —
		// 화면이 잘못 부른 것을 결과가 없는 것처럼 보이게 하지 않는다.
		if (manager && unassignedOnly) {
			throw new ApiException(MemberErrorCode.ROSTER_UNASSIGNED_FILTER_NOT_ALLOWED);
		}

		// 회차 지표 조인에 쓰는 매니저이자 목록을 좁히는 매니저다. 오퍼레이터는 null이다 —
		// manager_trainee_roster_view가 manager_assignment 기반이고 테이블정의서가 '오퍼레이터
		// 배정은 만들지 않는다'고 못박아, 오퍼레이터 ID로 조인해 봐야 어차피 한 행도 붙지 않는다.
		UUID scopedManagerId = manager ? currentUserResolver.resolveCurrentMemberId() : null;

		TraineeRosterService.RosterResult result = traineeRosterService.findRoster(
				cohortId, organizationId, scopedManagerId, scopedManagerId, assessmentRoundId,
				classroomId, unassignedOnly, accountStatus, query, sort,
				PageRequest.of(page, size));

		Page<TraineeRosterRepository.RosterRow> rosterPage = result.page();
		TraineeRosterResponse response = new TraineeRosterResponse(
				rosterPage.getContent().stream().map(TraineeRosterResponse.Trainee::from).toList(),
				rosterPage.getNumber(),
				rosterPage.getSize(),
				rosterPage.getTotalElements(),
				rosterPage.getTotalPages(),
				result.unassignedCount(),
				result.cohortTotal(),
				result.statusCounts().activeCount(),
				result.statusCounts().invitedCount(),
				result.statusCounts().inactiveCount(),
				result.rounds().stream().map(TraineeRosterResponse.RoundOption::from).toList(),
				result.assessmentRoundId()
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

					## 요청 (경로 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					| --- | --- | --- | --- |
					| `cohortId` | 필수 | UUID | 대상 교육생이 속한 기수 |
					| `traineeId` | 필수 | UUID | 명단 조회 응답의 `content[].traineeId`(사용자 ID) |

					## 요청 (본문)

					| 필드 | 필수 | 타입 | 설명 |
					| --- | --- | --- | --- |
					| `status` | 필수 | enum | `ACTIVE`(활성화) · `INACTIVE`(비활성화) |
					| `reason` | 선택 | string | 변경 사유. 감사 로그에 남는다 |

					```json
					{
					  "status": "INACTIVE",
					  "reason": "중도 이탈"
					}
					```

					⚠️ **`status`는 `ACTIVE`·`INACTIVE` 두 값만 받는다.** `INVITED`는 초대 흐름이
					설정하는 값이라 이 API의 대상이 아니다. 요청 타입 자체가 두 값만 받으므로 그 외 문자열은
					역직렬화 단계에서 `VALIDATION_FAILED`(400)로 거절된다.

					## 응답 (200)

					변경 후의 명단 **한 행**이며, 필드 구성은 `기수 교육생 명단 조회`의 `content[]` 항목과 같다.

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `traineeId` | UUID | 교육생 사용자 ID |
					| `name` | string | 이름 |
					| `email` | string | 이메일 |
					| `status` | enum | **변경 후** 계정 상태 |
					| `classroomId` | UUID? | 현재 소속 반. 배정이 없으면 `null` |
					| `className` | string? | 현재 소속 반 이름. 배정이 없으면 `null` |
					| `joinedAt` | date-time | 기수 등록일 |
					| `leftAt` | date-time? | 중도 이탈일. 이탈하지 않았으면 `null` |
					| `inactivatedReasonCode` | enum? | 비활성화 사유 코드. 활성이면 `null` |
					| `inactivatedReason` | string? | 비활성화 상세 사유. **INACTIVE여도 `null`일 수 있다** |
					| `inactivatedById` | UUID? | 비활성화한 사용자 ID. 활성이면 `null` |
					| `inactivatedByName` | string? | 비활성화한 사용자 이름. 화면 표시용 |
					| `inactivatedAt` | date-time? | 비활성화 시각. 활성이면 `null` |

					#### inactivatedReasonCode 값

					| 값 | 설명 |
					| --- | --- |
					| `RESIGNED` | 퇴사 |
					| `ADMIN_SUSPENDED` | 운영자 조치. 이 화면의 `비활성` 버튼이 넣는 값 |
					| `CONTRACT_ENDED` | 계약 종료 |
					| `SECURITY_ACTION` | 보안 조치 |
					| `OTHER` | 기타 |

					⚠️ **계정 상태와 기수 소속을 함께 바꾼다.** 화면이 한 행에 `계정 비활성`과
					`중도 이탈 {날짜}`를 같이 보여주기 때문이다.

					| 요청 `status` | `app_user.status` | `cohort_member.status` | `cohort_member.left_at` |
					| --- | --- | --- | --- |
					| `INACTIVE` | `INACTIVE` | `LEFT` | **현재 시각으로 기록** |
					| `ACTIVE` | `ACTIVE` | `ACTIVE` | `NULL`로 되돌림 |

					`left_at`을 되돌리는 것은 선택이 아니다 — 테이블정의서가 **ACTIVE면 `left_at IS NULL`,
					LEFT면 `left_at` 필수**를 요구하는데 DB CHECK는 status 값만 보고 둘의 정합성은 보지 않아,
					한쪽만 바꾸면 어긋난 채로 저장된다.

					⚠️ **비활성화하면 `inactivatedReasonCode`가 `ADMIN_SUSPENDED`로 기록된다.**
					이 화면에서 오는 정지는 전부 운영자 조치이기 때문이며, 요청의 `reason`은
					상세 사유(`inactivatedReason`)로 따로 남는다.

					⚠️ **초대 대기(`INVITED`) 교육생은 대상이 아니다.** 아직 계정이 활성화되지 않아 정지·재활성
					개념이 성립하지 않는다. 시도하면 409이며, 화면이 할 일은 초대 재발송이다.

					💡 **멱등이다.** 이미 같은 상태면 아무 것도 바꾸지 않고 현재 값을 그대로 돌려준다 —
					`left_at`도 다시 찍히지 않으므로 최초 이탈 시각이 보존된다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "상태 변경 성공"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED status가 비었거나 ACTIVE·INACTIVE 외의 값임"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 만료됨"),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED 오퍼레이터 권한이 아님"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND 기수를 찾을 수 없음 · TRAINEE_NOT_FOUND 그 기수에 그 교육생이 없음(다른 기수·다른 기관 포함)"),
			@ApiResponse(responseCode = "409", description = "TRAINEE_STATUS_NOT_MUTABLE 초대 대기(INVITED) 교육생이라 상태를 직접 바꿀 수 없음. 화면이 할 일은 초대 재발송이다"),
			@ApiResponse(responseCode = "500", description = "ORGANIZATION_CONTEXT_MISSING 인증 정보에서 기관을 확인할 수 없음")
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
		UUID organizationId = extractOrganizationId(authentication);
		UUID actorUserId = currentUserResolver.resolveCurrentMemberId();

		// 허용값 검사는 TraineeStatusUpdate 타입이 역직렬화 단계에서 대신한다.
		TraineeRosterRepository.RosterRow updated = traineeRosterService.updateStatus(
				cohortId, organizationId, traineeId, request.status().toAccountStatus(),
				request.reason(), actorUserId);
		return ResponseEntity.ok(TraineeRosterResponse.Trainee.from(updated));
	}

	@Operation(
			operationId = "resendTraineeInvitations",
			summary = "교육생 초대 재발송 | ✅ 사용 가능",
			description = """
					OP-06 `명단` 탭의 `초대 재발송` 액션. **고른 여러 명에게 한 번에** 다시 보낸다(11차 R2).

					## 받는 사람용 API와 다르다

					지금까지 교육생만 `POST /auth/invitations/resend`를 써야 했다. 그쪽은 **받는 사람용**이라
					계정 존재 여부를 숨기려 **항상 같은 202**를 주고, 쿨다운에 걸리면 202를 받고도 메일이 안 나간다 —
					오퍼레이터가 눌러도 나갔는지 알 수 없었다.

					이 API는 **운영자용**이다. 이미 인증으로 기관·권한을 확인했으므로 숨길 것이 없고,
					`invitationSentCount`로 **실제로 나간 수**를 답한다. 쿨다운도 없다.
					오퍼레이터·매니저 재발송과 같은 성격의 경로다.

					## 요청

					| 변수 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `cohortId` | **필수** | UUID | 경로. 기수 식별자 |
					| `traineeIds` | **필수** | UUID[] | 본문. 1~200명. 명단 응답의 `traineeId` |

					**헤더 (선택)** — `X-Request-Id: {문자열}` 추적용. 생략하면 서버가 만든다.

					재발송 버튼은 명단 응답의 **`pendingInvitationTokenId`가 `null`이 아닌 행**에서만 켜면 된다
					(같은 차수에 추가한 필드다). 예전처럼 `status === 'INVITED'`로 유추하지 않아도 된다.

					## 동작

					- **이전 토큰을 무효화하고 새로 발급한다** — 재발송 뒤에도 옛 링크가 살아 있으면 유효한 가입 링크가 둘이 된다
					- **초대 원장은 새로 만들지 않는다** — 재발송 횟수가 정확히 쌓인다
					- **만료된 초대도 대상이다** — 만료야말로 재발송이 필요한 주된 상황이다

					## 응답 (200) — 행별 부분 성공

					| 필드 | 설명 |
					|---|---|
					| `requestedCount` | 요청에 담긴 수 |
					| `invitationSentCount` | **실제로 메일이 나간 수** |
					| `failures[]` | 재발송하지 못한 행만. 전부 성공하면 빈 배열 |

					20명 중 하나가 이미 활성이라고 나머지 19명을 막지 않는다. **실패가 있어도 200**이므로
					`failures`를 확인해야 한다 — `NOT_FOUND`(명단이 낡음) · `NOT_PENDING`(이미 활성/취소됨) ·
					`NO_INVITATION`(초대 원장 없음).
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "재발송 처리 완료(행별 실패는 failures 참고)"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED traineeIds가 비었거나 200명을 넘음"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED 오퍼레이터 권한이 아님"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND 기수를 찾을 수 없거나 다른 기관의 기수"),
			@ApiResponse(responseCode = "500", description = "ORGANIZATION_CONTEXT_MISSING 인증 정보에서 기관을 확인할 수 없음"),
			@ApiResponse(responseCode = "502", description = "INVITE_MAIL_FAILED 메일 발송 실패")
	})
	@PreAuthorize("hasRole('OPERATOR')")
	@PostMapping(path = "/invitations/resend", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ResendTraineeInvitationsResponse> resendTraineeInvitations(
			@Parameter(description = "대상 교육생이 속한 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Valid @RequestBody ResendTraineeInvitationsRequest request,
			@Parameter(description = "재발송 요청 추적용 식별자이며 생략 시 서버가 생성합니다.", example = "resend-trainee-001")
			@RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
			Authentication authentication
	) {
		UUID organizationId = extractOrganizationId(authentication);
		return ResponseEntity.ok(ResendTraineeInvitationsResponse.from(
				traineeRosterService.resendInvitations(cohortId, organizationId, request.traineeIds(), requestId)));
	}

	private UUID extractOrganizationId(Authentication authentication) {
		Object details = authentication.getDetails();
		if (!(details instanceof UUID organizationId)) {
			throw new ApiException(AcademicOperationsErrorCode.ORGANIZATION_CONTEXT_MISSING);
		}
		return organizationId;
	}
}
