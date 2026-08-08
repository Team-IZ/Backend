package com.bigproject.backend.domain.analytics.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 위험 교육생 격자의 행 계층.
 *
 * TEAM은 반과 프로젝트를 모두 좁혀야 성립한다. 팀 번호는 반 안에서만 유일하고
 * team은 project_id에 종속이라, 회차 범위가 여러 프로젝트에 걸치면 같은 팀 행을 이어 붙일 수 없다.
 * manager_team_heatmap_view 명세도 "프로젝트가 바뀌면 팀 이름으로 추이를 연결하지 않습니다"라고
 * 못 박는다.
 */
@Schema(name = "RiskTraineeLevel",
		description = "위험 교육생 격자의 행 계층. CLASS(반 단위) · TEAM(팀 단위, projectId와 classroomId 한 건이 모두 필요)",
		enumAsRef = true)
public enum RiskTraineeLevel {
	CLASS,
	TEAM
}
