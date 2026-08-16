package com.bigproject.backend.domain.projectexecution.presentation;

import com.bigproject.backend.domain.projectexecution.presentation.dto.AutoAssignTeamsRequest;
import java.util.List;
import com.bigproject.backend.domain.projectexecution.infrastructure.ProjectMembershipQueryRepository;
import com.bigproject.backend.domain.projectexecution.domain.AssignmentMethod;
import com.bigproject.backend.domain.projectexecution.application.TeamService;
import com.bigproject.backend.domain.projectexecution.domain.Team;
import com.bigproject.backend.domain.projectexecution.domain.TeamMembership;
import com.bigproject.backend.domain.projectexecution.presentation.dto.AssignTeamMemberRequest;
import com.bigproject.backend.domain.projectexecution.presentation.dto.CreateTeamRequest;
import com.bigproject.backend.domain.projectexecution.presentation.dto.TeamListResponse;
import com.bigproject.backend.domain.projectexecution.presentation.dto.TeamResponse;
import com.bigproject.backend.domain.projectexecution.presentation.dto.UpdateTeamRequest;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * MG-08 팀 편성. {@code auto-assign}·{@code confirm}(잠금)은 아직 없다 — 확정 후 되돌리기를
 * 막는 "제출 존재 여부" 판정이 Submission 도메인(미착수)에 걸려 있어, 그 판정 없이 만들면
 * 제출이 시작된 뒤에도 편성을 되돌릴 수 있게 된다. Submission 도메인 착수 후 이어서 만든다.
 */
@Tag(name = "Project Execution", description = "프로젝트 구성·일정·요구사항 API")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;
    private final CurrentUserResolver currentUserResolver;


    @Operation(
            operationId = "findTeams",
            summary = "팀 목록 조회 | ✅ 사용 가능",
            description = """
					프로젝트의 팀 편성 현황을 조회한다(MG-08 팀 탭).

					**요청**
					- projectId (경로): 대상 프로젝트 ID

					**응답 (200)**
					- teams[]: 팀 목록(teamId · **classId** · **className** · teamNumber · name ·
					  status · memberCount · **members[]**)
					- unassignedMembers[] / unassignedCount: 아직 어느 팀에도 속하지 않은 인원 —
					  화면의 "팀에 들어가지 않은 사람이 n명 있어요" 배너가 이 값을 쓴다

					## 🔴 30차 R3 — 담당 반만 온다

					**매니저의 담당 반에 속한 팀만** 내려간다. 제출 현황(`findProjectSubmissionStatus`)·
					반별 진행(`findClassProgress`)과 같은 모집단이다. 담당 반이 없으면 빈 배열이다.

					종전에는 기수 전체(48팀)가 나왔다. 이 조회는 매니저 전용이라 오퍼레이터 갈래가 없다.

					## 🔴 30차 R4 — 반 이름과 구성원

					`teamNumber`는 **반 안에서만** 유일하다. 반이 여덟이면 `1팀`이 여덟 번 나오므로
					`className` 없이는 목록에서 팀을 구분할 수 없다. 담당 반이 둘 이상인 매니저가 있어
					담당 반으로 좁힌 뒤에도 이 값이 필요하다.

					`members[]`는 `unassignedMembers[]`와 **같은 모양**(projectMembershipId · userId ·
					name)이다 — 편성 화면이 사람을 두 목록 사이로 끌어다 옮기는 자리라 한 컴포넌트로
					다룰 수 있어야 한다. `memberCount`는 이 배열의 길이다.

					⚠️ 팀 편성 화면의 5단계(편성 전·편성 중·전원 배정·확정·제출 시작)를 이 응답 하나로
					전부 판정할 수는 없다 — "제출 시작됨" 여부는 Submission 도메인(미착수)이 있어야
					알 수 있어 지금은 status(DRAFT/CONFIRMED)와 unassignedCount까지만 내려준다.
					"""
    )
    @PreAuthorize("hasAnyRole('MANAGER')")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "팀 목록 조회 성공"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED 매니저가 아님"),
            @ApiResponse(responseCode = "404", description = "PROJECT_NOT_FOUND 프로젝트를 찾을 수 없음"),
    })
    @GetMapping("/projects/{projectId}/teams")
    public ResponseEntity<TeamListResponse> findTeams(
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        UUID managerUserId = currentUserResolver.resolveCurrentMemberId();
        var teams = teamService.findManagedTeams(projectId, orgId, managerUserId);
        var classNames = teamService.findClassNames(
                teams.stream().map(Team::getClassId).distinct().toList(), orgId);
        var members = teamService.findMembersByTeamIds(teams.stream().map(Team::getTeamId).toList());
        var unassigned = teamService.findUnassignedMembers(projectId, orgId);
        return ResponseEntity.ok(TeamListResponse.from(teams, classNames, members, unassigned));
    }

    @Operation(
            operationId = "createTeam",
            summary = "팀 생성 | ✅ 사용 가능",
            description = """
					빈 팀 하나를 만든다([+ 팀 추가] 버튼).

					**요청**
					- projectId (경로): 대상 프로젝트 ID
					- name (필수): 팀 이름

					**응답 (201)**
					- 생성된 팀 정보(teamId · teamNumber · name · status)

					⚠️ 팀이 속할 반(class)은 요청에 없다 — 로그인한 매니저가 이 프로젝트의 기수에서
					담당하는 반을 서버가 역산한다. 매니저가 한 기수에 반을 하나만 담당한다는 전제라,
					여러 반을 담당하면 400 MANAGER_CLASSROOM_AMBIGUOUS로 막힌다.
					"""
    )
    @PreAuthorize("hasAnyRole('MANAGER')")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "팀 생성 성공"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED 팀 이름 누락 · MANAGER_CLASSROOM_AMBIGUOUS 담당 반을 하나로 정할 수 없음"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED 매니저가 아님"),
            @ApiResponse(responseCode = "404", description = "PROJECT_NOT_FOUND 프로젝트를 찾을 수 없음"),
    })
    @PostMapping("/projects/{projectId}/teams")
    public ResponseEntity<TeamResponse> createTeam(
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId,
            @Valid @RequestBody CreateTeamRequest request
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        UUID managerUserId = currentUserResolver.resolveCurrentMemberId();
        Team team = teamService.createTeam(projectId, orgId, request.name(), managerUserId);
        String className = teamService.findClassNames(List.of(team.getClassId()), orgId)
                .get(team.getClassId());
        return ResponseEntity.status(HttpStatus.CREATED).body(TeamResponse.empty(team, className));
    }

    @Operation(
            operationId = "autoAssignTeams",
            summary = "팀 자동 배분 실행 | ✅ 사용 가능",
            description = """
					팀이 하나도 없을 때만 실행할 수 있다([자동 배분] 모달).

					**요청**
					- teamSize (필수): 팀 하나의 목표 인원
					- skillBalanced (필수): true면 직전 회차 도달 단계 기준 실력 섞기, false면 무작위

					**응답 (200)** — 생성된 팀 목록

					실력 섞기는 직전 회차(같은 카테고리 바로 앞 순번)의 응시 기록이 있어야 동작한다.
					1차 프로젝트이거나 직전 회차에 응시 기록이 하나도 없으면 무작위로 조용히 대체된다 —
					근거 없이 "실력 섞기"라 표시하지 않기 위해 화면에는 이 경우를 안내하는 것을 권한다.
					"""
    )
    @PreAuthorize("hasAnyRole('MANAGER')")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "자동 배분 성공"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED 필수값 누락 · NO_MEMBERS_TO_ASSIGN 배분할 인원 없음 · MANAGER_CLASSROOM_AMBIGUOUS 담당 반을 하나로 정할 수 없음"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED 매니저가 아님"),
            @ApiResponse(responseCode = "404", description = "PROJECT_NOT_FOUND 프로젝트를 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "AUTO_ASSIGN_NOT_ALLOWED 이미 팀이 편성되어 있음 — 자동 배분은 팀이 없을 때만 된다"),
    })
    @PostMapping("/projects/{projectId}/teams/auto-assign")
    public ResponseEntity<List<TeamResponse>> autoAssignTeams(
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId,
            @Valid @RequestBody AutoAssignTeamsRequest request
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        UUID managerUserId = currentUserResolver.resolveCurrentMemberId();
        List<Team> teams = teamService.autoAssign(
                projectId, orgId, request.teamSize(), request.skillBalanced(), managerUserId);
        var classNames = teamService.findClassNames(
                teams.stream().map(Team::getClassId).distinct().toList(), orgId);
        var members = teamService.findMembersByTeamIds(teams.stream().map(Team::getTeamId).toList());
        List<TeamResponse> response = teams.stream()
                .map(team -> TeamResponse.from(team, classNames.get(team.getClassId()),
                        members.getOrDefault(team.getTeamId(), List.of())))
                .toList();
        return ResponseEntity.ok(response);
    }

    @Operation(
            operationId = "confirmTeams",
            summary = "팀 편성 확정 | ✅ 사용 가능",
            description = """
					전원 배정 상태에서 편성을 확정한다(정의 문서 ③→④). 미배정 인원이 있으면 실패한다.

					**응답 (200)** — 본문 없음. 이후 학생이 코드를 제출할 수 있게 된다.
					"""
    )
    @PreAuthorize("hasAnyRole('MANAGER')")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "팀 편성 확정 성공"),
            @ApiResponse(responseCode = "400", description = "NO_TEAMS_TO_CONFIRM 팀이 없음 · TEAMS_NOT_READY 아직 미배정 인원이 있음"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED 매니저가 아님"),
    })
    @PostMapping("/projects/{projectId}/teams/confirm")
    public ResponseEntity<Void> confirmTeams(
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        teamService.confirmTeams(projectId, orgId);
        return ResponseEntity.ok().build();
    }

    @Operation(
            operationId = "reopenTeams",
            summary = "팀 편성 다시 열기 | ✅ 사용 가능",
            description = """
					확정된 편성을 다시 편성 중 상태로 되돌린다([편성 다시 열기] 버튼).

					⚠️ 제출이 시작된 뒤에도 이 API는 지금 막지 않는다 — 그 판정에 필요한 Submission
					도메인이 아직 없다. 도메인이 생기면 제출 존재 시 이 API를 막는 조건이 추가된다.

					**응답 (200)** — 본문 없음
					"""
    )
    @PreAuthorize("hasAnyRole('MANAGER')")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "편성 다시 열기 성공"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED 매니저가 아님"),
    })
    @PatchMapping("/projects/{projectId}/teams/reopen")
    public ResponseEntity<Void> reopenTeams(
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        teamService.reopenTeams(projectId, orgId);
        return ResponseEntity.ok().build();
    }

    @Operation(
            operationId = "updateTeam",
            summary = "팀 정보 수정 | ✅ 사용 가능",
            description = """
					팀 이름을 바꾼다(팀 행 클릭 → 편집).

					**요청**
					- projectId (경로): 기준 프로젝트 ID(현재 미사용, URL 구조상만 존재)
					- teamId (경로): 대상 팀 ID
					- name (필수): 새 팀 이름

					**응답 (200)** — 본문 없음
					"""
    )
    @PreAuthorize("hasAnyRole('MANAGER')")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "팀 정보 수정 성공"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED 팀 이름 누락"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED 매니저가 아님"),
            @ApiResponse(responseCode = "404", description = "TEAM_NOT_FOUND 팀을 찾을 수 없음"),
    })
    @PatchMapping("/projects/{projectId}/teams/{teamId}")
    public ResponseEntity<Void> updateTeam(
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId,
            @Parameter(description = "팀 ID") @PathVariable UUID teamId,
            @Valid @RequestBody UpdateTeamRequest request
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        teamService.renameTeam(teamId, orgId, request.name());
        return ResponseEntity.ok().build();
    }

    @Operation(
            operationId = "assignTeamMember",
            summary = "팀원 배정 | ✅ 사용 가능",
            description = """
					미배정 인원을 이 팀에 넣는다([팀에 넣기] 모달).

					**요청**
					- projectId (경로): 기준 프로젝트 ID
					- teamId (경로): 대상 팀 ID
					- projectMembershipId (필수): 배정할 사람. `GET /teams` 응답의 미배정 목록에서 얻는다

					**응답 (200)** — 본문 없음

					이미 다른 팀에 있던 사람이면 그 배정은 자동으로 닫히고 이 팀으로 옮겨진다.
					"""
    )
    @PreAuthorize("hasAnyRole('MANAGER')")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "팀원 배정 성공"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED projectMembershipId 누락 · PROJECT_MEMBERSHIP_NOT_FOUND 이 프로젝트의 참여자가 아님"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED 매니저가 아님"),
            @ApiResponse(responseCode = "404", description = "TEAM_NOT_FOUND 팀을 찾을 수 없음"),
    })
    @PostMapping("/projects/{projectId}/teams/{teamId}/members")
    public ResponseEntity<Void> assignMember(
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId,
            @Parameter(description = "팀 ID") @PathVariable UUID teamId,
            @Valid @RequestBody AssignTeamMemberRequest request
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
        teamService.assignMember(teamId, orgId, projectId, request.projectMembershipId(),
                AssignmentMethod.MANUAL, actorUserId);
        return ResponseEntity.ok().build();
    }

    @Operation(
            operationId = "removeTeamMember",
            summary = "팀원 제외 | ✅ 사용 가능",
            description = """
					팀에서 한 명을 뺀다(팀 행 클릭 → 편집 → 체크 해제).

					**요청**
					- projectId (경로): 기준 프로젝트 ID
					- teamId (경로): 대상 팀 ID
					- traineeId (경로): 뺄 사람의 사용자 ID

					**응답 (204)** — 본문 없음

					지우지 않고 배정 종료 시각만 찍는다 — 과거에 이 팀 소속이었다는 사실이 남는다.
					"""
    )
    @PreAuthorize("hasAnyRole('MANAGER')")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "팀원 제외 성공(본문 없음)"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED 매니저가 아님"),
            @ApiResponse(responseCode = "404", description = "TEAM_NOT_FOUND 팀을 찾을 수 없음 · PROJECT_MEMBERSHIP_NOT_FOUND 이 프로젝트의 참여자가 아님 · TEAM_MEMBERSHIP_NOT_FOUND 그 팀에 속한 인원이 아님"),
    })
    @DeleteMapping("/projects/{projectId}/teams/{teamId}/members/{traineeId}")
    public ResponseEntity<Void> removeMember(
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId,
            @Parameter(description = "팀 ID") @PathVariable UUID teamId,
            @Parameter(description = "뺄 사람의 사용자 ID") @PathVariable UUID traineeId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        teamService.removeMember(teamId, orgId, projectId, traineeId);
        return ResponseEntity.noContent().build();
    }
}