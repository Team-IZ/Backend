package com.bigproject.backend.domain.member.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * MG-06 상세의 `이력`을 읽는다. 뷰가 이벤트 한 행씩 내고 회차 문맥을 함께 실으므로
 * 회차 묶음을 여기서 만든다 -- 종전에는 이벤트 목록과 회차 목록을 따로 조회해
 * 페이지 경계에서 회차 머리글이 사라졌다.
 */
public interface TraineeTimelineRepository {

	/**
	 * 한 페이지를 <b>한 번의 왕복</b>으로 읽는다(32차 R9②).
	 *
	 * <h2>왜 셋을 합쳤나</h2>
	 *
	 * <p>종전에는 전체 건수·회차 차수·이벤트를 각각 조회했고, <b>셋이 모두 같은 뷰를 바깥
	 * {@code WHERE}로만 걸렀다.</b> 뷰는 그 조건을 안으로 밀어 넣지 못하면 매번 통째로 계산되므로,
	 * 화면 한 번에 <b>같은 계산이 세 번</b> 일어났다. 프론트 실측에서 {@code size}를 1로 줄여도
	 * 11.7초가 그대로였던 것이 이 고정 비용이다 — 회차 6개·이벤트 14건짜리 데이터였다.
	 *
	 * <p>구현은 뷰를 한 번만 훑도록 묶는다. 페이지 한 장에 필요한 세 값이 같은 모집단에서 나오므로
	 * 나눠 물을 이유가 없었다.
	 *
	 * @param cursorSequenceNo 이 차수 <b>미만</b>부터 읽는다. 첫 페이지면 null
	 * @param size             회차 수. 페이지 단위가 이벤트가 아니라 <b>회차</b>다 —
	 *                         회차 중간이 잘리면 화면이 그 회차를 반쪽만 그린다
	 */
	TimelinePage findPage(UUID managerId, UUID cohortId, UUID traineeId,
			String eventType, Integer cursorSequenceNo, int size);

	/**
	 * @param totalElements 조건에 맞는 이벤트 <b>전체</b> 건수. 화면 상단의 `이벤트 8건`이며
	 *                      페이지와 무관하다
	 * @param rows          이 페이지 회차들의 이벤트 전부. 회차 차수 내림차순, 회차 안에서는
	 *                      발생 시각 오름차순이라 화면이 위에서 아래로 그리는 순서와 같다
	 * @param hasNext       더 읽을 회차가 있는가. {@code size + 1}번째 회차의 존재로 판정한다
	 */
	record TimelinePage(int totalElements, List<EventRow> rows, boolean hasNext) {
	}

	/** 뷰 한 행. 회차 문맥이 이벤트마다 함께 실려 있다. */
	record EventRow(
			UUID assessmentRoundId, UUID projectId, Integer analysisSequenceNo,
			String roundName, String projectName,
			UUID teamId, String teamName,
			OffsetDateTime roundStartAt, OffsetDateTime roundEndAt,
			UUID eventId, String eventType, OffsetDateTime occurredAt,
			String sourceEntityType, UUID sourceEntityId, String sourceStatus,
			UUID sessionId, boolean expandable, String detailActionCode,
			String payload, String aggregationStatus, boolean stale, OffsetDateTime asOfAt) {
	}
}
