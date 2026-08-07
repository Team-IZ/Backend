package com.bigproject.backend.domain.disclosure.presentation.dto;

import com.bigproject.backend.domain.disclosure.domain.DisclosureScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 리포트 공개 범위 설정 요청. 매니저가 회차 결과를 교육생에게 열어 줄 때 쓴다.
 *
 * <p><b>필드가 하나뿐인 이유.</b> 공개 상태는 컬럼 4개지만 매니저가 정하는 것은 범위 하나이고,
 * 나머지 셋(상태·시각·주체)은 서버가 파생한다. 넷을 다 받으면 DB CHECK
 * {@code ck_report_trainee_release_status_2}를 어기는 조합을 클라이언트가 만들 수 있게 된다.
 *
 * <pre>
 * scope=PRIVATE          → withhold()  : WITHHELD · 시각·주체 NULL
 * scope=SUMMARY | FULL   → release()   : RELEASED · 시각=now · 주체=요청 매니저
 * </pre>
 *
 * <p>{@code NOT_CONFIGURED}로 되돌리는 값은 없다. 미지정은 "아직 아무도 정하지 않았다"는
 * 초기 상태이지 선택지가 아니다 — 한 번 열었다 닫는 것은 {@code PRIVATE}이다.
 */
@Schema(description = """
		리포트 공개 범위 설정 요청.

		`scope`의 `PRIVATE`은 비공개 확정(withhold), `SUMMARY`·`FULL`은 공개(release)다.
		`SUMMARY`는 축별 서술과 교안 위치까지, `FULL`은 문답 원문까지 연다.""")
public record UpdateReportDisclosureRequest(

		@NotNull
		DisclosureScope scope
) {
}
