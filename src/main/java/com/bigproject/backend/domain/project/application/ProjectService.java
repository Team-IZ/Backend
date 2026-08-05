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
}