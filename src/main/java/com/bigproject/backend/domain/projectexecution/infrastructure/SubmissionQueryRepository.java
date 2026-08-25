package com.bigproject.backend.domain.projectexecution.infrastructure;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * submission 조회 전용 포트. Submission 도메인 자체는 아직 이 저장소에 엔티티로 없지만
 * 테이블은 실제 DB에 존재하고 데이터도 있다 — 팀 편성이 "제출된
 * 팀은 구성을 못 바꾼다"를 지키려면 이 확인이 필요해 raw SQL로만 최소한으로 읽는다.
 */
public interface SubmissionQueryRepository {

    /**
     * 이 팀이 정상 접수(ACCEPTED)된 제출을 한 적이 있는지. FETCH_FAILED(저장소 접근 실패)는
     * 코드가 실제로 안 들어온 것이라 세지 않는다 — 그 상태만 있으면 팀 구성을 바꿔도 묶일
     * 코드가 없다.
     */
    boolean hasAcceptedSubmission(UUID teamId, UUID orgId);

    /**
     * 여러 팀을 <b>한 번에</b> 본다(46차 R3). 반 통째로 해체할 때 팀마다 위 메서드를 부르면
     * 팀 수만큼 질의가 나가고, 무엇보다 "어느 팀이 걸렸는지"를 모아서 알려 줄 수가 없다 —
     * 일괄 작업이 409로 끊길 때 화면이 팀 이름을 말할 수 있어야 한다.
     *
     * @return 정상 접수된 제출이 있는 팀 ID만. 없으면 빈 집합이다.
     */
    Set<UUID> findTeamIdsWithAcceptedSubmission(Collection<UUID> teamIds, UUID orgId);
}
