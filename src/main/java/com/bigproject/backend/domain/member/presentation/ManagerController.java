package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.academicoperations.domain.AcademicOperationsErrorCode;
import com.bigproject.backend.domain.member.application.ManagerRosterService;
import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.ManagerRosterRepository;
import com.bigproject.backend.domain.member.domain.ManagerRosterSort;
import com.bigproject.backend.domain.member.presentation.dto.ManagerRosterResponse;
import com.bigproject.backend.global.exception.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// 태그는 Member 도메인으로 합친다. 설명은 MemberController 쪽 @Tag가 대표로 싣는다.
@Tag(name = "Member")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RestController
@RequestMapping(value = "/managers", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ManagerController {

	private final ManagerRosterService managerRosterService;

	@Operation(
			operationId = "findManagers",
			summary = "매니저 목록 조회 | ✅ 사용 가능",
			description = """
					운영 관리 `매니저` 탭의 매니저 목록 표를 채운다. 상태 필터·이름 검색·정렬·페이지네이션을
					**모두 서버가 처리**하므로 화면은 파라미터만 넘기면 된다. 조회 범위인 기관은 액세스 토큰에서
					가져온다.

					## 요청 (쿼리 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					| --- | --- | --- | --- |
					| `cohortId` | 선택 | UUID | 담당 기수로 좁힌다. 화면 상단 기수 선택기에 대응. 비우면 기관 전체 |
					| `status` | 선택 | enum | `INVITED`(초대 대기) · `ACTIVE`(활성) · `INACTIVE`(정지). 비우면 전체(화면의 `상태 · 전체`) |
					| `query` | 선택 | string | 이름·이메일 부분검색. 비우면 전체 |
					| `sort` | 선택 | enum | `NAME`(이름순, 기본) · `ASSIGNED_TRAINEE_COUNT`(담당 인원순) |
					| `page` | 선택 | int | 0부터 시작. 기본 `0` |
					| `size` | 선택 | int | 페이지당 개수. 기본 `20`, 최대 `100` |

					⚠️ **`status`에 `LOCKED`는 쓸 수 없다.** 이 화면이 쓰지 않는 값이라 지정하면 400이다.

					## 응답 (200)

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `content[]` | array | 매니저 목록. 각 항목 구조는 아래 |
					| `page` | int | 현재 페이지(0부터) |
					| `size` | int | 페이지당 개수 |
					| `totalElements` | long | **필터 적용 후** 전체 건수 |
					| `totalPages` | int | 전체 페이지 수 |
					| `statusCounts` | object | 계정 상태별 인원. 화면 상단 `활성 7 · 초대 대기 1 · 정지 0` |

					### statusCounts (object)

					키는 계정 상태, 값은 인원 수입니다. 예: `{"INVITED": 1, "ACTIVE": 7, "INACTIVE": 0}`

					| 키 | 타입 | 설명 |
					| --- | --- | --- |
					| `INVITED` | long | 초대 대기 인원 |
					| `ACTIVE` | long | 활성 인원 |
					| `INACTIVE` | long | 정지 인원 |

					### content[] 각 항목

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `managerId` | UUID | 매니저 사용자 ID |
					| `name` | string? | 이름. 초대만 되고 미활성이면 `null`(화면에서는 `가입 대기`) |
					| `email` | string | 이메일 |
					| `status` | enum | `INVITED` · `ACTIVE` · `INACTIVE` |
					| `cohortId` | UUID? | 담당 기수 ID |
					| `cohortName` | string? | 담당 기수명 |
					| `classroomNames` | string[] | 현재 담당 반 이름 목록. 없으면 `[]`(화면의 `미배정`) |
					| `assignedTraineeCount` | long | 담당 반의 재학(ACTIVE) 교육생 합계. 없으면 `0` |
					| `lastLoginAt` | date-time? | 최근 로그인. 이력이 없으면 `null`(화면에서는 `—`) |
					| `invitedAt` | date-time? | 최초 초대 시각 |
					| `invitedByName` | string? | 초대한 사람 이름 |

					⚠️ **`statusCounts`는 상태·검색 필터와 무관한 모집단**이라 `totalElements`와 다르다.
					상태 칩이 자기 자신을 필터링하면 안 되므로 목록 한 페이지로는 만들 수 없다.
					`INVITED`·`ACTIVE`·`INACTIVE` 세 키가 **항상 모두 있고**, 0명인 상태는 `0`으로 온다 —
					키가 빠지는 것과 0명인 것은 다르다.

					⚠️ **단 `cohortId`는 필터가 아니라 조회 범위라 `statusCounts`에도 똑같이 걸린다.**
					여기만 기관 전체로 세면 칩 합계가 목록 건수와 어긋난다.

					💡 **`cohortId`를 넘기면 그 기수를 담당하는 매니저만 나온다.** 판정 기준은
					**그 기수 반에 활성 배정이 있거나, 그 기수를 대상으로 한 매니저 초대가 있는 것**이다.
					초대만 되고 아직 반이 없는 매니저(화면의 `가입 대기 · 미배정`)도 나와야 해서 OR로 본다.
					이때 `classroomNames`·`assignedTraineeCount`도 그 기수 것만 세므로, 과거 기수를 담당했던
					매니저의 이전 반이 현재 기수 화면에 섞이지 않는다.

					💡 **`cohortId`·`cohortName`은 가장 최근 매니저 초대의 담당 기수다.** 매니저 초대는
					`POST /members/organizations/{organizationId}/manager-invitations`에서 기수를 필수로
					지정하므로 초대 이력이 있는 매니저라면 항상 값이 있다.

					💡 **`invitedAt`·`invitedByName`은 같은 초대 행에서 읽는다.** 화면 비고가
					`2026-07-24 초대 · 김오퍼레이터`처럼 둘을 한 문장으로 쓰기 때문이다.

					💡 **다른 역할 목록은 각자의 화면 전용 API를 쓴다** — 오퍼레이터는 SA-02 오퍼레이터 목록,
					교육생은 `GET /cohorts/{cohortId}/trainees`다. 그래서 이 경로에는 역할 파라미터가 없다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "매니저 목록 조회 성공"),
			@ApiResponse(responseCode = "400", description = "ACCOUNT_STATUS_FILTER_NOT_SUPPORTED 이 화면이 쓰지 않는 계정 상태(LOCKED) · VALIDATION_FAILED page·size 값이 올바르지 않음"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 만료됨"),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED 오퍼레이터 권한이 아님"),
			@ApiResponse(responseCode = "500", description = "ORGANIZATION_CONTEXT_MISSING 인증 정보에서 기관을 확인할 수 없음")
	})
	@PreAuthorize("hasRole('OPERATOR')")
	@GetMapping
	public ResponseEntity<ManagerRosterResponse> findManagers(
			@Parameter(description = "담당 기수로 좁힌다. 생략하면 기관 전체",
					example = "123e4567-e89b-12d3-a456-426614174000")
			@RequestParam(required = false) UUID cohortId,
			@Parameter(description = "계정 상태 필터. INVITED/ACTIVE/INACTIVE만 지원하며 생략하면 전체")
			@RequestParam(required = false) AccountStatus status,
			@Parameter(description = "이름·이메일 부분검색", example = "강민서")
			@RequestParam(required = false) String query,
			@Parameter(description = "정렬 기준", example = "NAME")
			@RequestParam(required = false, defaultValue = "NAME") ManagerRosterSort sort,
			@Parameter(description = "0부터 시작하는 페이지 번호", example = "0")
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@Parameter(description = "페이지당 개수(최대 100)", example = "20")
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		UUID organizationId = extractOrganizationId(authentication);

		ManagerRosterService.RosterResult result = managerRosterService.findManagers(
				organizationId, cohortId, status, query, sort, PageRequest.of(page, size));

		Page<ManagerRosterRepository.ManagerRosterRow> managerPage = result.page();
		ManagerRosterResponse response = new ManagerRosterResponse(
				managerPage.getContent().stream().map(ManagerRosterResponse.Manager::from).toList(),
				managerPage.getNumber(),
				managerPage.getSize(),
				managerPage.getTotalElements(),
				managerPage.getTotalPages(),
				result.statusCounts()
		);
		return ResponseEntity.ok(response);
	}

	private UUID extractOrganizationId(Authentication authentication) {
		Object details = authentication.getDetails();
		if (!(details instanceof UUID organizationId)) {
			throw new ApiException(AcademicOperationsErrorCode.ORGANIZATION_CONTEXT_MISSING);
		}
		return organizationId;
	}
}
