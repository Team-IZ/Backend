package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.member.application.TraineeDetailService;
import com.bigproject.backend.domain.member.presentation.dto.TraineeDetailResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Member")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RestController
@RequestMapping(value = "/cohorts/{cohortId}/trainees", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('MANAGER')")
@RequiredArgsConstructor
public class TraineeDetailController {
	private final TraineeDetailService service;

	@Operation(
			operationId = "findManagerTraineeDetail",
			summary = "교육생 상세 조회 | ✅ 사용 가능",
			description = """
					매니저 교육생 상세(MG-06)의 **헤더와 회차별 도달 단계 격자**를 한 번에 채운다.
					그 아래 `이력`(세션·다시 보기·리포트·면담)은 별도 호출인
					`GET /cohorts/{cohortId}/trainees/{traineeId}/timeline`이 담당한다 — 화면 하나에 두 콜이다.

					## 왜 명단(`GET /cohorts/{cohortId}/trainees`)을 다시 쓰지 않는가

					| | 명단 | 상세 |
					| --- | --- | --- |
					| 회차 | `assessmentRoundId` **한 건**의 지표만 채운다 | 격자에 **전 회차**가 필요하다 |
					| 행 | 초대 대기(아직 계정이 없는 초대)도 한 행이다 | 계정이 있어야 상세가 성립한다 |
					| 모양 | 필터·정렬·페이지네이션 컬렉션 | 단건 |

					원천 뷰(`manager_trainee_roster_view`)의 행 단위가
					`(매니저, 기수, 사용자, 회차)`라 교육생 하나를 고르면 그 기수의 회차가 그대로 행으로
					나온다. 명단에 「회차 전부」 모드를 덧대는 대신 단건 리소스로 가른 이유다.

					## 요청 (경로 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					| --- | --- | --- | --- |
					| `cohortId` | 필수 | UUID | 대상 교육생이 속한 기수 |
					| `traineeId` | 필수 | UUID | 명단 응답의 `content[].traineeId`(사용자 ID) |

					## 응답 (200)

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `traineeId`·`name`·`email` | | 헤더 신원 |
					| `status` | enum | `INVITED` · `ACTIVE` · `INACTIVE` |
					| `classroomId`·`className` | | 현재 소속 반. 배정이 없으면 `null` |
					| `inactivatedReasonCode`·`inactivatedReason`·`inactivatedAt` | | 비활성 사유·일자. 활성이면 `null` |
					| `riskTypeCode` | string? | 헤더 위험 배지. **가장 최근에 응시한 회차** 하나의 판정 |
					| `riskReasonSummary` | string? | 그 배지의 판정식이며 그대로 표시하는 완성된 문장이다(`평균 도달 단계 2.33 → 1.67. 2단 미만 2개.`). **대괄호 태그는 붙지 않는다**(30차 R8) |
					| `excellentOccurrenceCount`·`excellentAssessmentSequenceNos` | | 우수 누적 |
					| `rounds[]` | array | 회차별 도달 단계 격자. **차수 오름차순** |

					### rounds[] 각 항목

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `assessmentRoundId` | UUID | 회차 ID |
					| `cohortRoundNo` | int | **기수 안의 회차 순번.** 화면의 `미프 3차`에서 3이 이 값 |
					| `roundNo` | int | 프로젝트 안의 회차 번호. 미니프로젝트는 **늘 1** |
					| `roundName`·`projectId`·`projectName` | | 회차·프로젝트 이름 |
					| `attemptId`·`resultStatus` | | 응시 시도. 미응시면 `attemptId`가 `null` |
					| `primaryStatusCode` | string? | 배지 한 칸에 넣을 단일 코드 |
					| `matchedRiskTypeCodes` | array | 그 회차에 걸린 위험 유형 전부 |
					| `riskReasonSummary` | string? | 그 회차 판정식 |
					| `terminalAt` | date-time? | `세션 중단 · 07-14`의 일자 |
					| `expectedConceptCount`·`lowStageConceptCount` | | `2단 미만` 칸의 분모·분자 |
					| `excellent` | boolean | 이번 회차도 우수인가 |
					| `teamId` | UUID? | 회차 당시 팀 |
					| `concepts[]` | array | 격자 한 줄의 칸들. **문항 번호 오름차순** |

					### concepts[] 각 항목

					| 필드 | 타입 | 설명 |
					| --- | --- | --- |
					| `problemId`·`problemNo` | | 문항 |
					| `conceptId` | UUID? | 검증 개념. 개인 기여 문항은 `null` |
					| `conceptName` | string | 격자 열 라벨(`인증 흐름`). 개념이 없으면 문제 제목 |
					| `generationStatus` | string | `GENERATED` · `NOT_GENERATED` |
					| `reachLevel` | int? | 도달 단계 0~4단 |

					⚠️ **`reachLevel`의 `null`은 0단이 아니다.** 코드에 근거가 없어 문항이 만들어지지
					않았거나(`NOT_GENERATED`) 한 축도 답하지 않은 경우다. 화면은 `▨ 문항 없음`·`—`로
					그려야 하며 0단으로 치환하면 문항을 못 받은 사람이 전부 최하 도달로 보인다.

					⚠️ **`lowStageConceptCount`의 `null`도 0이 아니다.** 답한 문항이 하나도 없어
					셀 수 없는 것(미응시·무효 응시)이며, `0`(전부 통과)과 뜻이 다르다.

					⚠️ **회차마다 검증 개념이 다르다.** `3단 → 1단`은 같은 개념이 나빠진 것이 아니라
					다른 개념을 물은 결과일 수 있으므로, 화면은 `concepts[].conceptName`을 반드시 함께 그린다.

					💡 **차수는 `cohortRoundNo`이지 `roundNo`가 아니다.** `roundNo`는
					`(project_id, round_no)`가 UNIQUE라 프로젝트마다 1부터 다시 시작하는데,
					미니프로젝트는 프로젝트당 회차가 1건뿐이라 전부 1이 된다.

					💡 **기수가 아직 회차를 열지 않았으면 `rounds`가 빈 배열이다.** 교육생 프로필은
					그대로 나온다 — 회차가 없는 것과 교육생이 없는 것은 다르다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "상세 조회 성공"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 만료됨"),
			@ApiResponse(responseCode = "403", description = "MANAGER_ROLE_REQUIRED 매니저 권한이 아님 · MANAGER_VIEWER_NOT_ACTIVE 계정·기관이 활성이 아님"),
			@ApiResponse(responseCode = "404", description = "MANAGER_SCOPE_NOT_FOUND 담당 반이 그 기수에 없음 · TRAINEE_NOT_FOUND 담당 범위 안에 그 교육생이 없음. 다른 반·다른 기관의 교육생도 존재를 알리지 않고 여기로 묶는다")
	})
	@GetMapping("/{traineeId}")
	public ResponseEntity<TraineeDetailResponse> findManagerTraineeDetail(
			@Parameter(description = "대상 교육생이 속한 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Parameter(description = "조회할 교육생의 사용자 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID traineeId,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		return ResponseEntity.ok(service.findDetail(authentication.getName(), cohortId, traineeId));
	}
}
