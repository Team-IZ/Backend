package com.bigproject.backend.domain.reporting.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

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
	 * @param status  {@code PUBLISHED} · {@code PENDING_PUBLISH} · {@code PENDING_VISIBILITY}
	 *                · {@code NOT_ATTEMPTED} · {@code VOID_ATTEMPT} · {@code STOPPED}
	 * @param publishAfter {@code PENDING_PUBLISH}에서만. 이 시각 이후에 발행된다.
	 * @param concepts {@code PUBLISHED}에서만.
	 * @param retryState {@code NONE} · {@code PENDING} · {@code DONE}. PUBLISHED에서만.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record RoundReportResponse(
			String id,
			String label,
			String status,
			String publishAfter,
			String publishedAt,
			String curriculum,
			List<ConceptReportResponse> concepts,
			String retryState,
			String retryDueAt,
			String retryCompletedAt
	) {
	}

	/**
	 * 개념 하나의 결과.
	 *
	 * @param level 도달 단계 <b>0~4</b>. 0은 통과한 축이 하나도 없다는 뜻이며
	 *              "안 물어본 것"이 아니라 "못한 것"이다 — 화면이 이 둘을 섞으면 안 된다.
	 *              (DB {@code reach_display_code} L0~L4, AI {@code reachedStage} 0~4와 같은 눈금)
	 * @param said  학생에게 보여주는 서술. 공개 범위가 SUMMARY 미만이면 비어 있다.
	 * @param isRetryTarget 다시 보기 대상인가.
	 * @param curriculumRef 교안 위치. 공개 범위 SUMMARY 이상일 때만.
	 * @param qa    문답 원문. <b>공개 범위 FULL일 때만</b> 채워진다.
	 * @param explain 막힌 이유 해설. 다시 보기 대상일 때만.
	 * @param comparedReach 다시 보기 전/후 비교. 다시 보기를 마쳤을 때만.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ConceptReportResponse(
			String name,
			int level,
			String said,
			boolean isRetryTarget,
			CurriculumRefResponse curriculumRef,
			List<QaEntryResponse> qa,
			List<String> explain,
			ComparedReachResponse comparedReach
	) {
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
