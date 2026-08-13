package com.bigproject.backend.domain.reporting.presentation.dto;

import com.bigproject.backend.domain.disclosure.domain.DisclosureScope;
import com.bigproject.backend.domain.reporting.domain.ReportCompletionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * TR-04 `내 리포트` 응답. Frontend {@code src/features/trainee/report/types.ts}의
 * {@code ReportsData}와 1:1이다 — 프론트는 {@code getReports()} 한 번만 부르고
 * 이 응답 하나로 좌측 회차 레일과 우측 본문을 모두 그린다.
 *
 * <p>회차 목록과 본문을 한 응답에 담는 이유는 화면이 마스터-디테일이라 회차를 바꿀 때마다
 * 왕복하면 이미 받은 것을 다시 받게 되기 때문이다. 회차 수는 기수당 6~8개라 크기도 유계다.
 *
 * @param rounds       좌측 레일. 정렬은 서버가 정한다(최신 회차가 앞).
 * @param reportsById  {@code rounds[].id}(= assessmentRoundId)로 찾는 회차별 본문.
 */
public record TraineeReportsResponse(
		List<RoundListItem> rounds,
		Map<String, RoundReportResponse> reportsById
) {

	/**
	 * 좌측 레일 한 줄.
	 *
	 * @param id             회차 식별자(assessmentRoundId). 뷰 명세상 회차 선택의 권위 키다.
	 * @param hasPendingRetry 아직 안 한 다시 보기가 있다 — 레일에 점으로 표시된다.
	 */
	public record RoundListItem(String id, String label, boolean hasPendingRetry) {
	}

	/**
	 * 회차 하나의 본문. 프론트의 {@code RoundReport} 유니온을 <b>평평한 레코드 + status 판별자</b>로
	 * 표현한다. 상태별로 쓰지 않는 필드는 {@link JsonInclude}로 <b>키 자체가 빠지므로</b>
	 * TS의 선택 필드({@code publishAfter?})와 정확히 맞는다 — null을 실어 보내면
	 * {@code undefined}를 기대하는 쪽과 어긋난다.
	 *
	 * @param id      <b>회차</b> 식별자(assessmentRoundId)다. 리포트 ID가 아니다 —
	 *                미응시 회차는 리포트 행 자체가 없어 목록의 키로 쓸 수 없기 때문이다.
	 * @param reportId 이 회차의 리포트 식별자. <b>회차당 최대 1건</b>이다
	 *                ({@code uq_report_active_user}가 (assessment_round_id, user_id, report_type)로
	 *                유일성을 건다). 아직 리포트가 만들어지지 않은 회차({@code NOT_ATTEMPTED} 등)에서는
	 *                키가 빠진다. {@code GET /reports/{reportId}} 단건 조회에 이 값을 쓴다 —
	 *                {@code id}(회차 ID)로 부르면 404다.
	 * @param status  {@code PUBLISHED} · {@code PENDING_PUBLISH} · {@code PENDING_VISIBILITY}
	 *                · {@code NOT_ATTEMPTED} · {@code VOID_ATTEMPT} · {@code STOPPED}
	 * @param publishAfter {@code PENDING_PUBLISH}에서만. 이 시각 이후에 발행된다.
	 * @param disclosureScope 이 리포트의 공개 범위. 발행·공개 전이거나 리포트가 없으면 키가 빠진다.
	 *                <b>화면이 {@code qa} 유무로 범위를 되짚지 않게</b> 하려고 값으로 내려준다 —
	 *                {@code FULL}인데 문항이 아직 없어 {@code qa}가 비는 경우를 {@code SUMMARY}로
	 *                오인하는 것을 막는다.
	 * @param completionStatus 리포트가 <b>몇 개 문제로 만들어졌는가</b>. {@code PUBLISHED}에서만.
	 *                자세한 뜻은 아래 주석을 볼 것 — 같은 이름이 OP-05에서는 다른 뜻이다.
	 * @param concepts {@code PUBLISHED}에서만.
	 * @param retryState {@code NONE} · {@code PENDING} · {@code DONE}. PUBLISHED에서만.
	 */
	public record RoundReportResponse(
			String id,
			@JsonInclude(JsonInclude.Include.NON_NULL) String reportId,
			String label,
			@Schema(allowableValues = {"PUBLISHED", "PENDING_PUBLISH", "PENDING_VISIBILITY",
					"NOT_ATTEMPTED", "VOID_ATTEMPT", "STOPPED"})
			String status,
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(description = "PENDING_PUBLISH에서만. 그 외에는 키가 빠진다") String publishAfter,
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(description = "PUBLISHED에서만. 그 외에는 키가 빠진다") String publishedAt,
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(description = "PUBLISHED에서만. 그 외에는 키가 빠진다") String curriculum,
			@JsonInclude(JsonInclude.Include.NON_NULL) DisclosureScope disclosureScope,

			// 여기 PARTIAL은 "일부 문제의 AI 생성이 실패해 개념 카드가 빠졌다"는 뜻이다.
			// OP-05의 동명 값과 뜻이 다르니 설명은 ReportCompletionStatus javadoc을 볼 것 —
			// 설명을 필드가 아니라 타입에 둔 이유도 거기 적혀 있다.
			@JsonInclude(JsonInclude.Include.NON_NULL) ReportCompletionStatus completionStatus,

			/*
			 * 🔴 19차 Q1 — "생성 실패와 문항 없음이 화면에서 같아 보인다"에 답하는 두 값이다.
			 *
			 * 두 사건은 **원천이 다르고 응답에 나타나는 방식도 다르다.**
			 *
			 *   문항 없음  → concepts[]에 asked=false 카드로 **들어온다**
			 *                (assessment_problem.generation_status='NOT_GENERATED', 분석 단계)
			 *   생성 실패  → concepts[]에서 **아예 빠진다**
			 *                (report_generation_item.status<>'SUCCEEDED', 리포트 생성 단계)
			 *
			 * 즉 생성 실패가 asked=false로 오는 일은 없다. 다만 "빠진다"는 것만으로는 화면이
			 * 몇 개가 왜 없는지 알 수 없어서, 그 건수를 여기서 직접 준다.
			 */
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(description = """
					이 리포트가 만들려 한 문제 수. `concepts[]` 길이와 비교하면 몇 개가 빠졌는지 알 수 있다.
					**3으로 하드코딩하지 말 것** — 문항 수는 회차 설정에 따라 달라진다.""")
			Integer expectedConceptCount,

			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(description = """
					**AI 생성이 실패해 빠진 개념 수**(시스템 장애). `0`이면 빠진 것이 없다.

					`asked: false`(문항 없음)와 **다른 사건**이다 — 그쪽은 학생 코드에 개념이 없어
					묻지 못한 정상 상태이고 `concepts[]`에 카드로 들어온다. 이 값은 결과가 나왔어야
					하는데 못 나온 것이라 학생 잘못이 아니며, *"묻지 않았어요"* 로 안내하면 안 된다.

					`completionStatus=PARTIAL`의 원인 건수이기도 하다.""")
			Integer missingConceptCount,

			@JsonInclude(JsonInclude.Include.NON_NULL) List<ConceptReportResponse> concepts,
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(description = "PUBLISHED에서만", allowableValues = {"NONE", "PENDING", "DONE"}) String retryState,
			@JsonInclude(JsonInclude.Include.NON_NULL) String retryDueAt,
			@JsonInclude(JsonInclude.Include.NON_NULL) String retryCompletedAt
	) {
	}

	/**
	 * 개념 하나의 결과.
	 *
	 * @param problemId 이 개념을 물은 문항 식별자. <b>개념 이름은 회차마다 반복된다</b>
	 *                  (`예외 처리와 롤백 전략`이 1·2·4차에 모두 나온다) — 이름으로 개념을 지목하면
	 *                  다시 보기 대상을 잘못 짚을 수 있어 안정 키를 함께 내려준다.
	 * @param asked 물었는가. <b>{@code false}면 이 개념은 그 학생 코드에 없어 문항이 만들어지지
	 *              않았다</b>({@code assessment_problem.generation_status='NOT_GENERATED'}). 그때는
	 *              {@code level}을 포함한 아래 값들이 전부 빠진다.
	 * @param level 도달 단계 <b>0~4</b>. 0은 통과한 축이 하나도 없다는 뜻이며
	 *              "안 물어본 것"({@code asked=false})이 아니라 "못한 것"이다 — 화면이 이 둘을 섞으면 안 된다.
	 *              눈금은 AI {@code reachedStage} 0~4와 같다. <b>DB {@code reach_display_code}는 원천이
	 *              아니다</b> — 미니프로젝트에서 항상 L0라 쓸 수 없다
	 *              ({@code JdbcTraineeReportQueryRepository.findConcepts} 주석 참고).
	 * @param said  학생에게 보여주는 서술. 공개 범위가 SUMMARY 미만이면 비어 있다.
	 * @param isRetryTarget 다시 보기 대상인가.
	 * @param curriculumRef 교안 위치. 공개 범위 SUMMARY 이상일 때만.
	 * @param qa    문답 원문. <b>공개 범위 FULL일 때만</b> 채워진다.
	 * @param explain 막힌 이유 해설. 다시 보기 대상일 때만.
	 * @param comparedReach 다시 보기 전/후 비교. 다시 보기를 마쳤을 때만.
	 *
	 * <h2>🔴 프론트엔드 수정 필요 — 선택 필드가 화면 타입에서 필수다</h2>
	 *
	 * <p>이 레코드는 {@link JsonInclude}로 <b>키 자체를 뺀다</b>(null을 싣지 않는다).
	 * 그런데 Frontend {@code trainee/report/types.ts}의 {@code ConceptReport}는
	 * {@code said: string}과 {@code qa: QaEntry[]}를 <b>필수</b>로 선언한다
	 * ({@code curriculumRef?}만 선택이다).
	 *
	 * <p>그래서 공개 범위가 {@code PRIVATE}·{@code SUMMARY}인 리포트에서 화면이
	 * {@code concept.qa.map(...)}을 무조건 부르면 {@code TypeError}가 난다.
	 *
	 * <p><b>백엔드 계약은 바꾸지 않는다.</b> 빈 값({@code ""}·{@code []})을 채워 보내면
	 * "빈 배열"과 "공개 범위상 안 열림"을 구분할 수 없게 되고, 화면은 문답이 없는 개념과
	 * 볼 권한이 없는 개념을 같은 모양으로 그리게 된다.
	 *
	 * <p>프론트에서 {@code said?}·{@code qa?}로 바꾸고 {@code ConceptCard.tsx}·{@code QaList.tsx}에
	 * 미존재 분기를 두면 된다.
	 */
	public record ConceptReportResponse(
			@Schema(nullable = true) String problemId,
			String name,

			/*
			 * 🔴 문항 없음 — 제3의 값. level=0 과 합치면 안 된다.
			 *
			 * false 면 그 학생 코드에 이 개념이 없어 문항 자체가 만들어지지 않았다는 뜻이며,
			 * 이때 level 을 포함한 아래 값들이 전부 빠진다. 0단(물었는데 통과한 축이 없다)과
			 * 섞으면 학생에게 "못했다"고 말하게 되는데 사실은 묻지 않은 것이다.
			 *
			 * GET /reports/class-diagnosis 가 level0 과 unasked 를 엄격히 구분하는 것과 같은 규칙이다.
			 */
			boolean asked,

			/** 도달 단계 0~4. {@code asked=false}면 키가 빠진다 — 물은 적이 없으므로 단계가 없다. */
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(minimum = "0", maximum = "4", example = "2", nullable = true,
					description = """
							통과한 축의 최댓값. **0~4 이외의 값은 나가지 않는다.**

							| 값 | 뜻 |
							|---|---|
							| `0` | 물었지만 통과한 축이 하나도 없다. **1로 올리지 않는다** |
							| `1` | 코드 이해까지 |
							| `2` | 설계 논리까지(왜 이렇게 했나) |
							| `3` | 대안 비교까지(다른 방법은) |
							| `4` | 반례 대응까지(언제 깨지나) — 전부 통과 |

							🔴 **`asked=false`면 이 키가 아예 빠진다.** 문항이 만들어지지 않은 개념이라
							단계를 말할 대상이 없다. `0`(물었는데 못했다)과 섞으면 화면이 학생에게
							"못했다"고 말하게 되는데 사실은 묻지 않은 것이다. `asked=true`인데 빠지는
							경우는 없다.""")
			Integer level,

			@JsonInclude(JsonInclude.Include.NON_NULL) String said,
			boolean isRetryTarget,
			@JsonInclude(JsonInclude.Include.NON_NULL) CurriculumRefResponse curriculumRef,
			@JsonInclude(JsonInclude.Include.NON_NULL) List<QaEntryResponse> qa,
			@JsonInclude(JsonInclude.Include.NON_NULL) List<String> explain,
			@JsonInclude(JsonInclude.Include.NON_NULL) ComparedReachResponse comparedReach
	) {

		/**
		 * 묻지 못한 개념. <b>이름만 있고 판정이 없다.</b>
		 *
		 * <p>{@code isRetryTarget}이 항상 {@code false}인 이유는 다시 볼 문항이 없기 때문이다 —
		 * 재시험 대상은 "물었는데 2단 미만"이지 "묻지 못함"이 아니다.
		 */
		public static ConceptReportResponse unasked(String problemId, String name) {
			return new ConceptReportResponse(
					problemId, name, false, null, null, false, null, null, null, null);
		}
	}

	/** 교안 위치. 화면의 `교안 3장 · 36~46쪽` 줄을 만든다. */
	public record CurriculumRefResponse(String chapter, String pages, String title) {
	}

	/**
	 * 문답 한 줄.
	 *
	 * @param questionLabel 화면에 붙는 슬롯 이름(`질문` · `힌트 1` · `힌트 2`).
	 */
	public record QaEntryResponse(String questionLabel, String question, String answer) {
	}

	/** 다시 보기 전/후 도달 단계. 둘 다 0~4다. */
	public record ComparedReachResponse(int before, int after) {
	}
}
