package com.bigproject.backend.domain.reporting.presentation;

import com.bigproject.backend.domain.reporting.application.CohortReportService;
import com.bigproject.backend.domain.reporting.presentation.dto.CohortDiagnosisResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * OP-05 리포트. 교육생용 {@code ReportController}와 경로 앞부분(`/reports`)을 공유하지만
 * 역할(오퍼레이터)과 자원(기수 문서)이 달라 컨트롤러를 나눴다.
 *
 * <p>{@code /reports/class-diagnosis}는 리터럴이라 {@code /reports/{reportId}}보다
 * 먼저 매칭된다(Spring이 리터럴 패턴을 우선한다).
 */
@Tag(name = "Reporting", description = "리포트 발행 이력·본문·문답 조회 API (v2 IA: TR-04 / OP-05)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping(value = "/reports", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class CohortReportController {

	private final CohortReportService cohortReportService;

	@Operation(
			operationId = "findClassDiagnosis",
			summary = "수업 진단 리포트 조회 | ✅ 사용 가능",
			description = """
					OP-05 `리포트` 화면 전체를 이 응답 하나로 그린다. **섹션 5개가 한 문서**다 —
					① 요약 · ② 회차별 · ③ 개념별 도달 분포 · ④ 반별 위험자·집단 미달 · ⑤ 우수 교육생.

					## 매핑표의 4개가 1개로 합쳐졌다

					매핑표에는 `class-diagnosis` · `cohort-closing` · `.../group-gaps` ·
					`.../top-performers` 4개였다. 빅프로젝트가 제품에서 빠지면서
					(Frontend #93·#94) `수업 진단`·`기수 결산` 2탭의 존재 이유가 사라져 탭이
					하나로 합쳐졌고, 나머지 셋은 이 응답의 **필드**가 됐다.

					**발행 시점에 얼린 스냅샷**이라 나눠 부르면 서로 다른 스냅샷을 볼 위험도 있다.

					## 요청

					| 파라미터 | 위치 | 필수 | 타입 | 설명 |
					|---|---|---|---|---|
					| `cohortId` | 쿼리 | **필수** | UUID | 기수 식별자 |

					필터·드릴다운이 없는 고정 스냅샷이라 파라미터가 이것 하나뿐이다(정의서 §4).

					🔴 **프론트엔드 경로 수정 필요.** Frontend `operator/report/_/api/api.ts`의
					주석은 `GET /reports/{cohortId}`인데 실제 경로는
					`GET /api/v0/reports/class-diagnosis?cohortId=` 다.
					`GET /reports`는 이미 `hasRole('TRAINEE')`가 점유하고 있어 `/{cohortId}` 형태를
					쓸 수 없다 — 같은 경로에 역할별로 다른 응답을 주면 안 되기 때문이다.
					현재 프론트는 목(`loadReport()`) 단계라 연동 시 주석 처리된 `http<Report>()`
					줄을 이 경로로 고쳐야 한다.

					## `status` 가 응답의 절반을 결정한다

					| 값 | 뜻 | 함께 오는 것 |
					|---|---|---|
					| `IN_PROGRESS` | 기수 결산이 아직 발행 전 | `publishedAt`·`periodEnd` 가 `null`, **`topStudents`·`classRisk`·`groupShortfalls` 가 빈 배열** |
					| `CONFIRMED` | 결산까지 발행됨 | 전부 채워짐 |

					⚠️ **`IN_PROGRESS` 에서 세 배열이 빈 것은 오류가 아니다.** 아직 끝나지 않은
					회차의 결과를 미리 보여주지 않기 위한 것이다. `publishedAt` 이 `null` 인 것이
					PDF 를 잠그는 근거이기도 하다.

					서버 내부적으로는 리포트가 **두 벌**(수업 진단·기수 결산)이고 스냅샷도 따로다.
					이 API 가 둘을 합쳐 문서 하나로 낸다.

					## 도달 단계 분포 — `distribution`

					| 필드 | 설명 |
					|---|---|
					| `level0` | **통과한 축이 하나도 없음.** 물었는데 못한 것 |
					| `level1`~`level4` | 그 단계까지 통과 |
					| `unasked` | **묻지 못함** — 그 학생 코드에 개념이 없어 문항이 안 만들어짐 |

					🔴 **`level0` 과 `unasked` 를 합치면 안 된다.** "못한 것"과 "안 물어본 것"은 다르다.
					`belowLevel2Count` 는 **`level0 + level1 + level2`** 이며 `unasked` 는 빠진다.

					## 표기 형식

					| 필드 | 형식 | 예 |
					|---|---|---|
					| `curriculumVersion` | `v` + 버전 번호 | `v2` |
					| `section` | `제목 (p.시작–끝)` | `네트워킹 (p.28–40)` |

					`section` 의 구분자는 하이픈이 아니라 **en-dash(–, U+2013)** 다.

					## 값의 출처와 아직 비어 있는 값

					| 필드 | 상태 |
					|---|---|
					| `excluded` | `report_snapshot.summary_payload` 의 `excluded` 키에서 읽는다(2026-08-08). **생성 파이프라인이 아직 이 키를 안 채우면 `{0,0,0}`** 이다 |
					| `groupShortfalls[].round` | **항상 `""`.** 이 지표의 grain 이 반×개념이라 회차 축이 없다 |

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 404 `COHORT_REPORT_NOT_FOUND` | 없는 기수 **또는 수업 진단 리포트가 아직 만들어지지 않음** |

					회차가 하나도 안 끝났으면 정상적으로 404 다 — 빈 문서를 그리지 않는다.
					"""
	)
	@PreAuthorize("hasRole('OPERATOR')")
	@GetMapping("/class-diagnosis")
	public ResponseEntity<CohortDiagnosisResponse> findClassDiagnosis(@RequestParam UUID cohortId) {
		return ResponseEntity.ok(cohortReportService.findClassDiagnosis(cohortId));
	}
}
