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
	 * 조건에 맞는 이벤트 <b>전체</b> 건수. 화면 상단의 `이벤트 8건`이며 페이지와 무관하다.
	 */
	int countEvents(UUID managerId, UUID cohortId, UUID traineeId, String eventType);

	/**
	 * 회차 차수를 최신순으로 {@code limit}개까지. 페이지 단위가 이벤트가 아니라 <b>회차</b>다 --
	 * 회차 중간이 잘리면 화면이 그 회차를 반쪽만 그린다.
	 *
	 * @param cursorSequenceNo 이 차수 <b>미만</b>부터 읽는다. 첫 페이지면 null
	 */
	List<Integer> findRoundSequenceNos(UUID managerId, UUID cohortId, UUID traineeId,
			String eventType, Integer cursorSequenceNo, int limit);

	/**
	 * 주어진 차수들에 속한 이벤트 전부. 회차 차수 내림차순, 회차 안에서는 발생 시각 오름차순이라
	 * 화면이 위에서 아래로 그리는 순서와 같다.
	 */
	List<EventRow> findEvents(UUID managerId, UUID cohortId, UUID traineeId,
			String eventType, List<Integer> sequenceNos);

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
