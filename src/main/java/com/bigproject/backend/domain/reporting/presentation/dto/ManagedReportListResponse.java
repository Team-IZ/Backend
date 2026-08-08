package com.bigproject.backend.domain.reporting.presentation.dto;

import com.bigproject.backend.domain.reporting.domain.ManagedReportQueryRepository.ManagedReportRow;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 매니저가 담당하는 반의 리포트 목록. {@code PUT /reports/{reportId}/disclosure}의 <b>대상을 고르는</b> 화면이 읽는다.
 *
 * <p>본문(개념·서술·문답)은 <b>들어 있지 않다.</b> 이 API는 "어느 리포트가 발행됐고 지금 공개
 * 상태가 무엇인가"만 답한다 — 매니저가 교육생의 리포트 내용을 열람하는 것은 별개 정책이라
 * 여기서 슬쩍 열지 않는다.
 *
 * <p>배열을 그대로 내지 않고 객체로 감싼 이유는 이 저장소의 다른 목록 응답
 * ({@code CohortListResponse}·{@code EnrollmentListResponse})과 같은 모양을 쓰기 위해서다.
 * 나중에 총 건수·페이지 정보를 붙일 자리도 생긴다.
 */
public record ManagedReportListResponse(List<Item> reports) {

	public static ManagedReportListResponse from(List<ManagedReportRow> rows) {
		return new ManagedReportListResponse(rows.stream().map(Item::from).toList());
	}

	/**
	 * 목록 한 줄.
	 *
	 * @param releaseStatus 판별자다. {@code NOT_CONFIGURED}면 화면이 `공개 범위 미지정`으로 그린다 —
	 *                      <b>빈 리포트로 그리면 안 된다.</b> 결과는 이미 확정됐고 범위만 안 정한 상태다.
	 * @param scope         {@code NOT_CONFIGURED}이면 키 자체가 빠진다. {@code WITHHELD}는
	 *                      "정해서 닫았다"는 뜻이라 {@code PRIVATE}을 남긴다 — 미지정과 구분해야 한다.
	 * @param bodyVisible   교육생이 지금 본문을 읽을 수 있는가. 발행·공개가 모두 갖춰져야 참이다
	 *                      ({@code ReportDisclosureResponse.bodyVisible}과 같은 규칙).
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Item(
			UUID reportId,
			UUID assessmentRoundId,
			String roundName,
			int roundNo,
			UUID traineeUserId,
			String traineeName,
			UUID classId,
			String className,
			Instant publishedAt,
			String releaseStatus,
			String scope,
			Instant releasedAt,
			boolean bodyVisible
	) {

		static Item from(ManagedReportRow row) {
			return new Item(
					row.reportId(),
					row.assessmentRoundId(),
					row.roundName(),
					row.roundNo(),
					row.traineeUserId(),
					row.traineeName(),
					row.classId(),
					row.className(),
					row.publishedAt(),
					row.traineeReleaseStatus(),
					// 미지정이면 scope 컬럼이 NULL이라 그대로 빠진다(@JsonInclude).
					row.traineeDisclosureScope(),
					row.traineeReleasedAt(),
					isBodyVisible(row)
			);
		}

		/**
		 * {@code Report#isVisibleToTrainee}와 같은 판정이다. 엔티티를 로드하지 않고 목록 행에서
		 * 계산하는 이유는 이 API가 읽기 전용 목록이라 리포트 수만큼 엔티티를 띄울 이유가 없어서다.
		 * 판정 규칙이 두 곳에 생기는 것은 감수한다 — 대신 규칙이 바뀌면 둘 다 고쳐야 한다.
		 */
		private static boolean isBodyVisible(ManagedReportRow row) {
			return "ACTIVE".equals(row.lifecycleStatus())
					&& row.publishedAt() != null
					&& "RELEASED".equals(row.traineeReleaseStatus());
		}
	}
}
