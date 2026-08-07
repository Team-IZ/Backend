package com.bigproject.backend.domain.disclosure.presentation.dto;

import com.bigproject.backend.domain.disclosure.domain.DisclosureScope;
import com.bigproject.backend.domain.reporting.domain.Report;
import com.bigproject.backend.domain.reporting.domain.TraineeReleaseStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * 리포트 1건의 공개 상태. TR-04 `공개 범위 미지정` 상태를 그리는 근거다.
 *
 * <p>{@code releaseStatus}가 판별자이고 나머지는 그 값에 딸린다.
 * 쓰지 않는 필드는 {@link JsonInclude}로 <b>키 자체가 빠진다</b>
 * ({@code TraineeReportsResponse}와 같은 규칙 — null을 실어 보내지 않는다).
 *
 * <pre>
 * NOT_CONFIGURED → scope·releasedAt 없음        · 화면: `공개 범위 미지정`
 * WITHHELD       → scope=PRIVATE · releasedAt 없음 · 화면: 회차는 보이되 본문 잠김
 * RELEASED       → scope=SUMMARY|FULL · releasedAt 있음 · 화면: `PUBLISHED`
 * </pre>
 *
 * @param bodyVisible   본문을 읽을 수 있는가. {@code lifecycle=ACTIVE && publishedAt≠null &&
 *                      releaseStatus=RELEASED}를 모두 만족해야 참이다 —
 *                      <b>발행과 공개는 다른 사건</b>이라 둘 중 하나만으로는 열리지 않는다.
 * @param visibleFields 공개 범위가 본문의 어느 필드까지 여는가. 프론트가 범위→필드 규칙을
 *                      다시 구현하지 않도록 서버가 계산해 준다.
 */
@Schema(description = "리포트 공개 상태")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportDisclosureResponse(

		@Schema(description = "리포트 식별자")
		UUID reportId,

		@Schema(description = "어느 회차의 리포트인가")
		UUID assessmentRoundId,

		@Schema(description = "공개 상태 판별자", example = "RELEASED")
		TraineeReleaseStatus releaseStatus,

		@Schema(description = "공개 범위. NOT_CONFIGURED이면 키가 없다", example = "SUMMARY")
		DisclosureScope scope,

		@Schema(description = "발행 시각. 발행 전이면 키가 없다")
		Instant publishedAt,

		@Schema(description = "공개 처리 시각. RELEASED에서만 있다")
		Instant releasedAt,

		@Schema(description = "교육생이 본문을 읽을 수 있는가")
		boolean bodyVisible,

		@Schema(description = "공개 범위별로 열리는 본문 필드")
		VisibleFields visibleFields
) {

	/**
	 * 공개 범위 → 본문 필드 매핑. {@code ReportController} javadoc의 표와 같은 값이다.
	 *
	 * <pre>
	 * PRIVATE·미지정 : said ❌ · curriculumRef ❌ · qa ❌
	 * SUMMARY        : said ⭕ · curriculumRef ⭕ · qa ❌
	 * FULL           : said ⭕ · curriculumRef ⭕ · qa ⭕
	 * </pre>
	 *
	 * @param said          학생에게 보여주는 축별 서술
	 * @param curriculumRef 교안 위치({@code chapter}·{@code pages}·{@code title})
	 * @param qa            문답 원문. {@code [내 답변] 펼침}이 이 값으로 열린다
	 */
	@Schema(description = "공개 범위별 본문 필드 노출 여부")
	public record VisibleFields(boolean said, boolean curriculumRef, boolean qa) {

		private static final VisibleFields NONE = new VisibleFields(false, false, false);
		private static final VisibleFields SUMMARY = new VisibleFields(true, true, false);
		private static final VisibleFields FULL = new VisibleFields(true, true, true);

		static VisibleFields of(DisclosureScope scope) {
			if (scope == null) {
				return NONE;
			}
			return switch (scope) {
				case FULL -> FULL;
				case SUMMARY -> SUMMARY;
				case PRIVATE -> NONE;
			};
		}
	}

	/**
	 * 공개되지 않은 리포트는 범위를 실어 보내지 않는다 — {@code RELEASED}가 아니면
	 * {@code scope}는 화면이 쓸 값이 아니라 내부 상태다({@code WITHHELD}의 PRIVATE 포함).
	 * 다만 {@code WITHHELD}는 "정해서 닫았다"는 뜻이라 값을 남긴다 — 미지정과 구분해야 한다.
	 */
	public static ReportDisclosureResponse from(Report report) {
		return new ReportDisclosureResponse(
				report.getReportId(),
				report.getAssessmentRoundId(),
				report.getTraineeReleaseStatus(),
				report.getTraineeDisclosureScope(),
				report.getPublishedAt(),
				report.getTraineeReleasedAt(),
				report.isVisibleToTrainee(),
				VisibleFields.of(report.getTraineeDisclosureScope())
		);
	}
}
