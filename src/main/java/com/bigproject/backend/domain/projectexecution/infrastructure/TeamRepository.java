package com.bigproject.backend.domain.projectexecution.infrastructure;

import com.bigproject.backend.domain.projectexecution.domain.Team;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 🔴 <b>해체된 팀은 없는 팀이다.</b> 팀 해체는 행을 지우지 않고 {@code deleted_at}만 찍는
 * 소프트 삭제인데(과거 소속·제출 귀속을 남겨야 한다), 이 저장소에는 그 필터가 없어
 * 모든 읽기가 해체된 팀을 함께 돌려줬다(46차 R1·R2).
 *
 * <p>증상은 세 갈래였다.
 * <ul>
 *   <li>{@code DELETE /teams/{teamId}}가 204를 주고 커밋까지 정상인데 목록에 그대로 남았다</li>
 *   <li>같은 이름으로 다시 만들면 살아 있는 행과 부딪혀 409 {@code DATA_INTEGRITY_VIOLATION}</li>
 *   <li>제출 현황(네이티브 SQL)은 {@code deleted_at IS NULL}을 지켜서, 같은 반을 두고
 *       팀 목록은 "있다", 제출 현황은 "없다"({@code NOT_STARTED})고 답했다</li>
 * </ul>
 *
 * <p>그래서 필터 없는 조회 메서드는 남겨 두지 않는다 — 하나만 남아도 다음 호출부가 그것을 쓴다.
 */
public interface TeamRepository extends JpaRepository<Team, UUID> {

    Optional<Team> findByTeamIdAndOrgIdAndDeletedAtIsNull(UUID teamId, UUID orgId);

    // 프로젝트 하나에 속한 살아 있는 팀 전체 목록
    List<Team> findByProjectIdAndOrgIdAndDeletedAtIsNull(UUID projectId, UUID orgId);

    /**
     * 팀명 중복 사전 검사. <b>검사 범위를 DB 제약과 같게 맞춘다</b> —
     * {@code uq_team_project_id_class_id_name}이 활성 범위 부분 인덱스이므로 여기도
     * {@code deleted_at IS NULL}이다(2026-08-25_team_unique_active_scope.sql).
     *
     * <p>사전 검사는 SELECT-then-INSERT라 동시 요청 두 건이 둘 다 통과할 수 있고, 마이그레이션이
     * 아직 안 걸린 DB에서는 제약이 이 검사보다 넓다. 어느 쪽이든 마지막에 DB가 끊으므로
     * {@code TeamService}가 {@code saveAndFlush} 자리에서 그 예외도 같은 도메인 코드로 옮긴다.
     */
    boolean existsByProjectIdAndClassIdAndNameAndDeletedAtIsNull(UUID projectId, UUID classId, String name);
}
