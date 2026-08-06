package com.bigproject.backend.domain.project.application;

import com.bigproject.backend.domain.project.domain.ProjectRequirement;

import java.util.List;
import java.util.UUID;

public interface ProjectService {

    /**
     * 프로젝트 요구사항 전체를 교체한다. 화면은 개별 추가·삭제가 아니라
     * 항상 배열 전체를 다시 보낸다 — diff는 이 메서드 안에서 계산한다.
     *
     * - 기존에 있었는데 이번 목록에 없다 → retire()
     * - 이번 목록에만 있는 새 문구 → 신규 생성 (version_no=1)
     * - 문구가 완전히 같다 → 활성 유지, sequence_no만 갱신
     *
     * ⚠ 문구 자체가 식별자다. "문구를 고쳤다"와 "지우고 새로 추가했다"는
     * 서버가 구분할 수 없다 — 완전 동일 문구가 아니면 항상 후자로 처리된다.
     */
    /**
     * 첫 checkpoint(추후 AssessmentRound)가 열릴 때 호출되어 프로젝트를 진행 중으로 전환한다.
     * ⚠ CheckpointService는 폐기 예정 구세대 파일 — AssessmentRound 도메인 작업 시 이 호출부 자체가 교체될 것.
     */
    void markRunning(UUID projectId, UUID orgId, UUID actorUserId);
    List<ProjectRequirement> replaceRequirements(
            UUID projectId, UUID orgId, List<String> requirementTitles, UUID actorUserId);

    /**
     * 화면 표시용 "미프 N차" 라벨을 계산한다. 저장하지 않고 조회할 때마다 다시 계산한다 —
     * project.sequence_no(기수 전체 프로젝트 순번)와는 다른 값이다.
     * 그 기수의 MINI_PROJECT만(삭제 제외) sequence_no 순으로 다시 1부터 번호를 매긴 것이 이 값이다.
     *
     * ⚠ BIG_PROJECT는 이 라벨 체계 밖이다 — BIG_PROJECT의 projectId로 호출하면 400.
     */
    String resolveMiniProjectRoundLabel(UUID projectId, UUID orgId);

    /**
     * curriculum MG-09 "쓰인 회차" 탭용 — 이 teachesId(공용 개념 원장)가 검증 개념으로
     * 확정된 프로젝트들의 "미프 N차" 라벨 목록을 반환한다. 활성(ACTIVE) 세트에 속한 것만 포함한다 —
     * 교체돼서 SUPERSEDED된 과거 세트는 "현재 쓰인 회차"가 아니므로 뺀다.
     *
     * @return 라벨 목록. 하나도 안 쓰였으면 빈 리스트("—" 표시는 화면 책임).
     */
    List<String> findRoundLabelsUsingTeaches(UUID teachesId, UUID orgId);
}