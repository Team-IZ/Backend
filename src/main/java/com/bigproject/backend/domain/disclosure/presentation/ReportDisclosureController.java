package com.bigproject.backend.domain.disclosure.presentation;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.disclosure.application.ReportDisclosureService;
import com.bigproject.backend.domain.disclosure.presentation.dto.ReportDisclosureResponse;
import com.bigproject.backend.domain.disclosure.presentation.dto.UpdateReportDisclosureRequest;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 리포트 공개 상태. 경로는 {@code /reports} 아래지만 <b>Reporting이 아니라 Disclosure</b>다 —
 * 리포트가 주소를 가진 리소스이므로 그 하위 자원으로 붙었을 뿐이고,
 * "무엇을 발행했나"(Reporting)와 "누구에게 어디까지 열려 있나"(Disclosure)는 다른 책임이다.
 *
 * <p>{@code /reports/{reportId}/disclosure}는 {@code ReportController}의
 * {@code /reports/{reportId}}보다 경로가 길어 충돌하지 않는다.
 *
 * <p>예외는 {@code ReportExceptionHandler}가 받는다 — 전역 {@code @RestControllerAdvice}라
 * 패키지가 달라도 잡히고, 같은 리소스에 에러 응답 형식이 둘이 되는 것을 막는다.
 */
@Tag(name = "Disclosure", description = "리포트 공개 범위 조회·설정 API (v2 IA: TR-04 / MG-08)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping(value = "/reports/{reportId}/disclosure", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ReportDisclosureController {

	private final ReportDisclosureService reportDisclosureService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(
			operationId = "findMyDisclosure",
			summary = "내 리포트 공개 상태 조회 | ✅ 사용 가능",
			description = """
					TR-04에서 **본문이 안 열리는 이유**를 판별한다. `GET /reports`가 회차 상태까지
					같이 주므로 화면이 매번 부를 필요는 없고, `PENDING_VISIBILITY`처럼
					**공개 쪽 사정으로 잠긴 회차**를 눌렀을 때 확인용으로 쓴다.

					**교육생 본인 것만 나간다.** `traineeId`를 받지 않고 인증 주체로 결정한다.

					## 요청

					| 변수 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `reportId` | **필수** | UUID | 리포트 식별자. `assessmentRoundId`가 아니다 |

					## 응답

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `releaseStatus` | enum | **판별자.** `NOT_CONFIGURED` · `WITHHELD` · `RELEASED` |
					| `scope` | enum? | `PRIVATE` · `SUMMARY` · `FULL`. 미지정이면 **키가 빠진다** |
					| `publishedAt` | instant? | 발행 시각. 발행 전이면 키가 빠진다 |
					| `releasedAt` | instant? | 공개 처리 시각. `RELEASED`에서만 |
					| `bodyVisible` | boolean | 본문을 읽을 수 있는가 |
					| `visibleFields` | object | `{said, curriculumRef, qa}` — 범위가 여는 필드 |

					## 상태 3종이 화면에서 뜻하는 것

					| `releaseStatus` | 화면 |
					|---|---|
					| `NOT_CONFIGURED` | `공개 범위 미지정` — **결과는 나와 있고 매니저가 여는 시점만 남았다** |
					| `WITHHELD` | 회차는 목록에 보이되 본문이 잠긴다 |
					| `RELEASED` | `PUBLISHED` |

					⚠️ **셋 다 정상 상태라 오류를 던지지 않는다.** 본문 조회가 막히는 것과 다르다 —
					이 API는 "왜 막혔나"를 알려 주는 쪽이므로 200으로 상태를 싣는다.

					## visibleFields — 범위→필드 규칙을 서버가 계산한다

					| 범위 | `said` | `curriculumRef` | `qa` |
					|---|---|---|---|
					| `PRIVATE`·미지정 | ❌ | ❌ | ❌ |
					| `SUMMARY` | ⭕ | ⭕ | ❌ |
					| `FULL` | ⭕ | ⭕ | ⭕ |

					프론트가 이 표를 다시 구현하면 서버와 어긋날 때 **학생에게 안 보여야 할 문답이
					보이는 쪽**으로 틀릴 수 있다.

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 404 `REPORT_NOT_FOUND` | 없는 리포트 **또는 남의 리포트** |

					남의 리포트를 403이 아니라 404로 돌려준다 — 403이면 그 id가 존재한다는 사실이 샌다.
					"""
	)
	@PreAuthorize("hasRole('TRAINEE')")
	@GetMapping
	public ResponseEntity<ReportDisclosureResponse> findMyDisclosure(@PathVariable UUID reportId) {
		UUID userId = currentUserResolver.resolveCurrentMemberId();
		return ResponseEntity.ok(reportDisclosureService.findForTrainee(userId, reportId));
	}

	@Operation(
			operationId = "updateDisclosure",
			summary = "리포트 공개 범위 설정 | ✅ 사용 가능",
			description = """
					담당 매니저가 회차 결과를 교육생에게 연다. TR-04의 `공개 범위 미지정`을
					푸는 유일한 경로다 — 이 호출이 없으면 리포트는 발행돼도 영원히 잠겨 있다.

					## 요청

					| 필드 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `scope` | **필수** | enum | `PRIVATE` · `SUMMARY` · `FULL` |

					`NOT_CONFIGURED`는 보낼 수 없다. 미지정은 아직 아무도 정하지 않은 초기 상태이지
					선택지가 아니며, 열었다 닫는 것은 `PRIVATE`이다.

					## scope가 상태 4컬럼을 결정한다

					| 보낸 값 | 결과 상태 | 시각·주체 |
					|---|---|---|
					| `PRIVATE` | `WITHHELD` | 비운다 |
					| `SUMMARY` · `FULL` | `RELEASED` | 지금 · 요청한 매니저 |

					클라이언트가 상태·시각·주체를 직접 보내지 않는 이유는 DB CHECK
					`ck_report_trainee_release_status_2`가 넷의 조합을 강제하기 때문이다 —
					따로 받으면 제약을 어기는 조합을 만들 수 있다.

					## 발행 전에도 정할 수 있다

					**발행(`publishedAt`)과 공개(`releaseStatus`)는 다른 사건**이라 순서를 강제하지 않는다.
					발행 전에 범위를 정해 두면 발행되는 순간 바로 열린다. 다만 `bodyVisible`은
					둘이 모두 갖춰져야 참이다.

					## 권한

					요청 매니저가 **지금 담당하는 반**의 교육생 리포트만 바꿀 수 있다.
					배정 이력이 해제된 반(`manager_assignment.unassigned_at`)과 이탈한 교육생
					(`cohort_member.left_at`)은 제외된다 — 지난 기수에 잠깐 담당했던 매니저가
					계속 공개 범위를 바꾸면 안 된다.

					## 오류

					| 코드 | 상황 |
					|---|---|
					| 400 `REPORT_DISCLOSURE_SCOPE_INVALID` | 도메인이 막는 조합(공개인데 범위가 비공개) |
					| 404 `REPORT_NOT_FOUND` | 없는 리포트 · **담당하지 않는 교육생** · 기수 단위 리포트 |

					담당하지 않는 경우도 403이 아니라 404다 — 존재 여부를 흘리지 않기 위해
					조회 실패와 권한 실패를 같은 응답으로 합쳤다.
					"""
	)
	@PreAuthorize("hasRole('MANAGER')")
	@PutMapping
	public ResponseEntity<ReportDisclosureResponse> updateDisclosure(
			@PathVariable UUID reportId,
			@Valid @RequestBody UpdateReportDisclosureRequest request
	) {
		AuthUser manager = currentUserResolver.resolveCurrentUser();
		return ResponseEntity.ok(
				reportDisclosureService.update(manager.userId(), manager.organizationId(), reportId, request)
		);
	}
}
