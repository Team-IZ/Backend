package com.bigproject.backend.domain.evaluation.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = """
		프로젝트 회차의 채점 결과 종합과 교육생 목록.

		결과 탭은 마스터-디테일이고 이 응답이 **왼쪽 목록과 '프로젝트 종합' 화면**을 담당한다.
		사람을 클릭했을 때 오른쪽에 그리는 축별 4단계와 채점 근거는
		`GET /projects/{projectId}/evaluations/{userId}`가 따로 준다 — 그쪽이 사람 수 × 개념 수 × 4배라
		목록만 보는 기본 진입에 함께 실을 값이 아니다.

		🔴 **범위는 호출한 매니저의 담당 반이다.** 프로젝트 전체가 아니다.
		""")
public record ProjectEvaluationSummaryResponse(
		UUID projectId,
		String projectName,
		UUID assessmentRoundId,
		@Schema(description = "회차 번호이며 프로젝트 안에서만 유일합니다.", example = "1")
		int roundNo,
		String roundName,
		@Schema(description = """
				회차 리포트가 발행됐는지. 발행 방식이 ROUND_BATCH 하나뿐이라 회차 단위 판정입니다.

				발행 전 집계는 아직 응시하지 않은 인원이 빠진 **임시 값**이며, 발행 시점의 값으로 굳습니다.
				""")
		boolean reportPublished,
		@Schema(description = "발행 시각. 발행 전이면 null입니다.", nullable = true)
		Instant publishedAt,
		@Schema(description = """
				결과를 그릴 수 있는지. 아직 아무도 응시를 마치지 않았으면 false이고, 화면은 표 대신
				"팀 편성·제출·응시가 끝나야 개념별 집계가 생깁니다" 빈 상태를 보여줍니다.
				""")
		boolean resultAvailable,
		Summary summary,
		@Schema(description = """
				집단 미달 경고. 한 개념에서 **유효 응시자의 절반을 넘는 인원**이 막히면 담습니다.

				이 개념은 개인 사유에서 빼고 여기서만 경고합니다 — 반 전체가 막힌 것을 개인 문제로
				읽으면 면담에서 엉뚱한 말을 하게 됩니다.
				""")
		List<ClassWarning> classWarnings,
		@Schema(description = "개념별 집계이며 표시 순서 오름차순입니다.")
		List<ConceptAggregate> conceptAggregates,
		@Schema(description = "교육생 목록이며 이름 오름차순입니다. 왼쪽 목록이 이 배열입니다.")
		List<Trainee> trainees
) {

	@Schema(name = "ProjectEvaluationSummary", description = """
			요약 카드 3장의 원자 값.

			`retryTargetCount`(다시 보기 대상)는 화면이 `failedCount + notAttendedCount`로 만듭니다 —
			발행 전에는 이 합을 보여주지 않기로 한 화면 규칙이 있어 서버가 미리 더하지 않습니다.
			""")
	public record Summary(
			@Schema(description = "회차 대상 인원(담당 반 기준)", example = "25")
			long totalCount,
			@Schema(description = "최초 응시를 마친 인원", example = "23")
			long attendedCount,
			@Schema(description = "개념 하나 이상에서 2단 미달인 인원(불합격). 응시를 마친 사람만 셉니다.", example = "4")
			long failedCount,
			@Schema(description = """
					**검증 세션을 하지 못한 인원**이며 세 사유(`NO_SHOW`·`NOT_SUBMITTED`·`ANALYSIS_FAILED`)의
					합입니다. 아직 창이 열려 있는 사람은 세지 않습니다.

					🆕 종전에는 창이 닫히도록 안 본 인원만 셌습니다. 반별 현황
					(`class-progress`)의 `notAttendedCount`와 **같은 기준**이라 두 화면의 숫자가 갈리지 않습니다.
					사유별 내역이 필요하면 `trainees[].notAttendedReason`을 세면 됩니다.
					""", example = "31")
			long notAttendedCount,
			@Schema(description = "무효 확정된 수행 인원. 집계 분모에서 빠집니다.", example = "0")
			long invalidCount
	) {
	}

	public record ClassWarning(
			UUID conceptId,
			String concept,
			@Schema(description = "그 개념에서 막힌 인원", example = "13")
			long stuckCount,
			@Schema(description = "판정 분모인 유효 응시자 수", example = "23")
			long assessedCount
	) {
	}

	@Schema(description = """
			개념 한 행.

			`notInCode`를 따로 세는 이유 — 그 개념이 코드에 없어 **문제가 만들어지지 않은** 것이라
			못한 것이 아닙니다. 막힌 사람과 같은 칸에 넣으면 매니저가 둘을 구분하지 못합니다.
			""")
	public record ConceptAggregate(
			UUID conceptId,
			String concept,
			int displayOrder,
			@Schema(description = "2단 미달로 막힌 인원", example = "3")
			long stuckCount,
			@Schema(description = "코드에 개념이 없어 문제를 받지 못한 인원", example = "1")
			long notInCodeCount,
			@Schema(description = "막힌 사람 명단")
			List<Person> stuck,
			@Schema(description = "코드에 없던 사람 명단")
			List<Person> notInCode
	) {
	}

	public record Person(UUID userId, String name) {
	}

	@Schema(name = "ProjectEvaluationTrainee",
			description = "교육생 한 명. 목록에 필요한 만큼만 담고 축별 단계는 상세 조회에서 옵니다.")
	public record Trainee(
			UUID userId,
			String name,
			UUID classId,
			String className,
			@Schema(description = """
					| 값 | 뜻 |
					|---|---|
					| `AVAILABLE` | 응시를 마쳐 결과가 있다 |
					| `IN_PROGRESS` | 아직 응시 중이거나 시작 전이다 |
					| `INCOMPLETE` | 끝내지 못한 채 응시 창이 닫혔다(중단) |
					| `NOT_ATTENDED` | **검증 세션을 하지 못했다.** 왜인지는 `notAttendedReason`에 있다 |
					| `INVALID` | 무효 확정된 수행이다 |

					🆕 **`NOT_ATTENDED`의 범위가 넓어졌습니다.** 종전에는 창이 닫히도록 안 본 경우만
					이 값이었고, **팀 미제출·코드 분석 실패는 값이 없어 `IN_PROGRESS`로 왔습니다** —
					그래서 이미 종료된 회차에 「응시 중」인 사람이 남았습니다. 셋 다 검증 세션을 하지
					못했다는 같은 사실이므로 한 값으로 묶고, **원인은 `notAttendedReason`으로 가릅니다.**

					🔴 **`AVAILABLE`이 아니면 합격·불합격을 말하지 않습니다.** 아직 풀지 않은 문제는 도달
					단계가 0이라, 판정을 걸면 응시 중인 사람이 전부 불합격으로 잡힙니다.
					""", example = "AVAILABLE")
			String resultStatus,
			@Schema(description = """
					검증 세션을 **왜** 하지 못했는지이며 `resultStatus`가 `NOT_ATTENDED`일 때만 값이 있습니다.

					| 값 | 뜻 | 매니저가 할 일 |
					|---|---|---|
					| `NO_SHOW` | 응시할 수 있었는데 창이 닫히도록 안 봤다 | 학생 독촉·면담 |
					| `NOT_SUBMITTED` | 팀이 제출하지 않아 문항이 만들어지지 않았다 | 팀에 재제출 안내 |
					| `ANALYSIS_FAILED` | 제출은 했으나 코드 분석이 실패했다 | 시스템 조치(학생 귀책 아님) |

					🔴 **`NO_SHOW`만 학생 책임입니다.** 나머지 둘은 응시할 문항 자체가 없어 **볼 수 없었던**
					경우이며, 특히 `ANALYSIS_FAILED`는 시스템 귀책이라 사유 없이 「미응시」로만 읽으면
					학생에게 불리한 기록이 됩니다. 제출 현황 탭이 `BLOCKED`와 `MISSED`를 가르는 축과 같습니다.
					""", example = "NOT_SUBMITTED", nullable = true)
			String notAttendedReason,
			@Schema(description = "코드에 있는데 2단 미달인 개념 수. 목록의 '막힘 N' 배지이며 `AVAILABLE`이 아니면 항상 0입니다.", example = "1")
			long stuckConceptCount,
			@Schema(description = "개념별 도달 결과이며 표시 순서 오름차순입니다.")
			List<ConceptOutcome> concepts
	) {
	}

	@Schema(description = """
			사람 × 개념의 도달 결과.

			`inCode=false`면 그 개념이 코드에 없어 묻지 못한 것이라 `reachLevel`·`retryTarget`을 읽지 않습니다.
			""")
	public record ConceptOutcome(
			UUID conceptId,
			String concept,
			int displayOrder,
			@Schema(description = "그 개념이 코드에 있어 문제가 만들어졌는지")
			boolean inCode,
			@Schema(description = "통과한 축 중 가장 높은 단계(0~4). 한 축이라도 미달하면 문제가 끝나므로 연속 통과 수와 같습니다.", example = "3")
			int reachLevel,
			@Schema(description = "2단 미달이라 다시 보기 대상인 개념인지. 코드에 없던 개념은 대상이 아닙니다.")
			boolean retryTarget
	) {
	}
}
