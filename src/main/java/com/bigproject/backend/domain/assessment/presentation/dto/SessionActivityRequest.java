package com.bigproject.backend.domain.assessment.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 응시 중 관찰 신호. <b>세 값 모두 선택이고 최소 하나는 있어야 한다.</b>
 *
 * <p>어느 문제의 어느 슬롯에 귀속되는지는 싣지 않는다 — 답변 제출과 같은 이유로 진행 위치는 서버
 * 커서가 정본이다. 클라이언트가 지목하게 두면 이미 닫힌 단계에 이탈 기록을 붙이는 요청이 만들어진다.
 *
 * <p><b>상한을 두는 이유는 값의 의미가 아니라 오버플로다.</b> {@code total_away_seconds}는 INTEGER
 * 누적 컬럼이고 Postgres는 정수 넘침을 조용히 감싸지 않고 오류로 끊는다 — 잘못된 값 하나가 이후
 * 그 세션의 모든 신호를 500으로 만든다. 하루를 넘는 이탈은 어차피 시간 상한이 먼저 세션을 닫는다.
 */
/*
 * 29차 R2 ① — 이 요청에는 required가 없는 것이 <b>사실</b>이다. 세 값 중 무엇을 보낼지는 그때
 * 관찰된 신호가 정하므로, 하나를 골라 required로 올리면 스펙이 거짓말을 한다.
 *
 * 대신 "빈 객체는 안 된다"를 minProperties로 적는다. 이것이 isEmpty()가 실제로 거절하는 것과 같고,
 * 생성기도 "전부 optional인데 최소 하나"를 그대로 읽는다.
 */
@Schema(description = """
		응시 중 관찰 신호(창 이탈·연결 끊김·첫 타이핑 지연).

		**세 값 모두 선택이지만 빈 객체는 400이다** — 최소 하나는 담아야 한다.""",
		minProperties = 1)
public record SessionActivityRequest(

		@Schema(description = """
				창을 떠나 있다 돌아온 시간(초). 보낼 때마다 이탈 횟수가 1 올라가므로
				**복귀 시점에 한 번만** 보낸다""", example = "42")
		@Min(0) @Max(86_400)
		Integer awaySeconds,

		@Schema(description = """
				연결이 끊겼다 돌아온 시간(초). 재연결 시점에 한 번만 보낸다.
				창 이탈과 달리 **세션 단위로만** 쌓인다 — 네트워크 장애는 특정 답변에 귀속시킬 성질이 아니다""",
				example = "8")
		@Min(0) @Max(86_400)
		Integer disconnectedSeconds,

		@Schema(description = """
				질문이 보인 뒤 첫 글자를 치기까지의 시간(ms). **슬롯당 한 번만 기록된다** —
				두 번째부터는 무시하므로 중복 전송이 안전하다""", example = "3500")
		@Min(0) @Max(86_400_000)
		Integer firstKeystrokeDelayMs
) {

	/** 셋 다 비어 있으면 쓸 일이 없는 요청이다. 조용히 200을 주면 화면 쪽 버그가 드러나지 않는다. */
	public boolean isEmpty() {
		return awaySeconds == null && disconnectedSeconds == null && firstKeystrokeDelayMs == null;
	}
}
