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
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId,
            @Parameter(description = "반 필터. 생략하면 담당 반 전체")
            @RequestParam(required = false) UUID classId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        UUID managerUserId = currentUserResolver.resolveCurrentMemberId();
        var teams = teamService.findManagedTeams(projectId, orgId, managerUserId, classId);
        var unassigned = teamService.findUnassignedMembers(projectId, orgId, managerUserId, classId);
        // 팀이 아직 없는 반에도 미배정 인원이 있다 — 두 쪽 반 ID를 합쳐서 이름을 읽는다.
        var classIds = java.util.stream.Stream.concat(
                        teams.stream().map(Team::getClassId),
                        unassigned.stream().map(ProjectMembershipQueryRepository.UnassignedMember::classId))
                .distinct().toList();
        var classNames = teamService.findClassNames(classIds, orgId);
        var members = teamService.findMembersByTeamIds(teams.stream().map(Team::getTeamId).toList());
        return ResponseEntity.ok(TeamListResponse.from(teams, classNames, members, unassigned));
    }

    @Operation(
            operationId = "createTeam",
            summary = "팀 생성 | ✅ 사용 가능",
            description = """
					빈 팀 하나를 만든다([+ 팀 추가] 버튼).

					**요청**
					- projectId (경로): 대상 프로젝트 ID
					- classId (필수): 팀을 만들 반
					- name (필수): 팀 이름

					**응답 (201)**
					- 생성된 팀 정보(teamId · classId · className · teamNumber · name · status)

					## 🔴 반은 요청이 정한다 (2026-08-25)

					종전에는 `classId`가 요청에 없었고, 로그인한 매니저가 이 기수에서 담당하는 반을 서버가
					**역산**했다. "매니저는 기수당 반 하나만 담당한다"는 전제였는데 사실이 아니었고
					(이도윤 = 7기 B·D반), 반이 둘 이상이면 400 `MANAGER_CLASSROOM_AMBIGUOUS`로 막혔다.
					**그 폴백과 에러 코드를 삭제했다.**

					반 목록은 `GET /cohorts/{cohortId}/classrooms`가 준다 — 매니저에게는 담당 반만 내려간다.

					## 🔴 팀 번호가 반 안에서 매겨진다

					종전 구현은 `프로젝트 전체 팀 수 + 1`이었다. DB 제약이
					`uq_team_project_id_class_id_team_number`(프로젝트 · 반 · 번호)라 반 내 유일이 정본이고,
					시드도 반마다 1부터다. 이제 그 반의 팀 수 + 1이다.
					"""
    )
    @PreAuthorize("hasAnyRole('MANAGER')")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "팀 생성 성공"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED classId·팀 이름 누락"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED 매니저가 아님 · CLASS_NOT_MANAGED 담당하지 않는 반"),
            @ApiResponse(responseCode = "404", description = "PROJECT_NOT_FOUND 프로젝트를 찾을 수 없음 · CLASS_NOT_FOUND 이 기수에 그 반이 없음"),
    })
    @PostMapping("/projects/{projectId}/teams")
    public ResponseEntity<TeamResponse> createTeam(
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId,
            @Valid @RequestBody CreateTeamRequest request
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        UUID managerUserId = currentUserResolver.resolveCurrentMemberId();
        Team team = teamService.createTeam(projectId, orgId, request.classId(), request.name(), managerUserId);
        String className = teamService.findClassNames(List.of(team.getClassId()), orgId)
                .get(team.getClassId());
        return ResponseEntity.status(HttpStatus.CREATED).body(TeamResponse.empty(team, className));
    }

    @Operation(
            operationId = "autoAssignTeams",
            summary = "팀 자동 배분 실행 | ✅ 사용 가능",
            description = """
					**그 반에** 팀이 하나도 없을 때만 실행할 수 있다([자동 배분] 모달).

					**요청**
					- classId (필수): 배분할 반
					- teamSize (필수): 팀 하나의 목표 인원
					- skillBalanced (필수): true면 직전 회차 도달 단계 기준 실력 섞기, false면 무작위

					**응답 (200)** — 그 반에 생성된 팀 목록

					## 🔴 반 단위로 돈다 (2026-08-25)

					| | 종전 | 지금 |
					| --- | --- | --- |
					| 배분 대상 | 프로젝트 전체 미배정 | **그 반의 미배정** |
					| 실행 조건 | 프로젝트에 팀 0개 | **그 반에 팀 0개** |
					| 생성 팀 수 | `ceil(전체인원 / teamSize)` | `ceil(반인원 / teamSize)` |

					종전 동작으로는 7기에서 기수 전원 249명이 한 반의 팀 63개로 들어갔고, 다른 반 매니저가
					먼저 팀을 만들면 내 반은 자동 배분을 영영 못 썼다(미프 5차가 그 상태였다 — J반에만
					팀 6개가 있어 B·D반이 409였다).

					실력 섞기는 직전 회차(같은 카테고리 바로 앞 순번)의 응시 기록이 있어야 동작한다.
					1차 프로젝트이거나 직전 회차에 응시 기록이 하나도 없으면 무작위로 조용히 대체된다 —
					근거 없이 "실력 섞기"라 표시하지 않기 위해 화면에는 이 경우를 안내하는 것을 권한다.
					"""
    )
    @PreAuthorize("hasAnyRole('MANAGER')")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "자동 배분 성공"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED 필수값 누락 · NO_MEMBERS_TO_ASSIGN 그 반에 배분할 인원 없음"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED 매니저가 아님 · CLASS_NOT_MANAGED 담당하지 않는 반"),
            @ApiResponse(responseCode = "404", description = "PROJECT_NOT_FOUND 프로젝트를 찾을 수 없음 · CLASS_NOT_FOUND 이 기수에 그 반이 없음"),
            @ApiResponse(responseCode = "409", description = "AUTO_ASSIGN_NOT_ALLOWED 그 반에 이미 팀이 있음 — 자동 배분은 그 반에 팀이 없을 때만 된다"),
    })
    @PostMapping("/projects/{projectId}/teams/auto-assign")
    public ResponseEntity<List<TeamResponse>> autoAssignTeams(
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId,
            @Valid @RequestBody AutoAssignTeamsRequest request
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        UUID managerUserId = currentUserResolver.resolveCurrentMemberId();
        List<Team> teams = teamService.autoAssign(projectId, orgId, request.classId(),
                request.teamSize(), request.skillBalanced(), managerUserId);
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
					**그 반의** 전원 배정 상태에서 편성을 확정한다(정의 문서 ③→④). 그 반에 미배정 인원이
					있으면 실패한다.

					**요청** — `classId`(쿼리, 필수)

					**응답 (200)** — 본문 없음. 이후 학생이 코드를 제출할 수 있게 된다.

					## 🔴 반 단위다 (2026-08-25)

					확정 대상도 `TEAMS_NOT_READY` 판정도 그 반만 본다. 종전에는 둘 다 프로젝트 전역이라
					**다른 반에 미배정이 남아 있으면 내 반 확정이 막혔다** — 반마다 편성 진도가 다른 것이
					정상인데 가장 늦은 반이 나머지를 전부 붙잡고 있었다.
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
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId,
            @Parameter(description = "확정할 반", required = true)
            @RequestParam UUID classId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        UUID managerUserId = currentUserResolver.resolveCurrentMemberId();
        teamService.confirmTeams(projectId, orgId, classId, managerUserId);
        return ResponseEntity.ok().build();
    }

    @Operation(
            operationId = "reopenTeams",
            summary = "팀 편성 다시 열기 | ✅ 사용 가능",
            description = """
					**그 반의** 확정된 편성을 다시 편성 중 상태로 되돌린다([편성 다시 열기] 버튼).
					`classId`(쿼리)가 필수다.

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
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId,
            @Parameter(description = "되돌릴 반", required = true)
            @RequestParam UUID classId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        UUID managerUserId = currentUserResolver.resolveCurrentMemberId();
        teamService.reopenTeams(projectId, orgId, classId, managerUserId);
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
        teamService.renameTeam(teamId, orgId, request.name(), currentUserResolver.resolveCurrentMemberId());
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
        teamService.removeMember(teamId, orgId, projectId, traineeId,
                currentUserResolver.resolveCurrentMemberId());
        return ResponseEntity.noContent().build();
    }

    @Operation(
            operationId = "disbandTeam",
            summary = "팀 해체 | ✅ 사용 가능",
            description = """
					팀을 해체한다(32차 R14①).

					**요청**
					- projectId (경로): 기준 프로젝트 ID
					- teamId (경로): 해체할 팀 ID

					**응답 (204)** — 본문 없음

					## 팀원은 미배정으로 돌아간다

					팀 행만 지우면 그 사람들이 **해체된 팀에 속한 채** 남아 어디에도 안 보입니다.
					배정 종료 시각을 찍어 미배정으로 돌려놓으므로 자동 배분·수동 배정의 대상이 됩니다.

					팀원이 0명인 팀도 그대로 해체됩니다 — 인원 없는 팀이 제출 현황에서
					「미제출 ⚠」로 잡혀 조치가 필요한 것처럼 보이던 자리입니다.

					## 지우지 않고 종료 시각만 찍는다

					팀원 제외(`DELETE …/members/{traineeId}`)와 같습니다. 과거에 이 팀 소속이었다는
					사실이 남아야 그 회차의 제출·결과 귀속이 유지됩니다.

					⚠️ **정상 접수된 제출이 있는 팀은 해체할 수 없습니다**(`409 TEAM_SUBMISSION_LOCKED`).
					해체하면 그 제출이 팀 없이 뜹니다. 배정·제외와 같은 규칙입니다.
					"""
    )
    @PreAuthorize("hasAnyRole('MANAGER')")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "팀 해체 성공(본문 없음)"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED 매니저가 아님"),
            @ApiResponse(responseCode = "404", description = "TEAM_NOT_FOUND 팀을 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "TEAM_SUBMISSION_LOCKED 정상 접수된 제출이 있는 팀은 해체할 수 없음"),
    })
    @DeleteMapping("/projects/{projectId}/teams/{teamId}")
    public ResponseEntity<Void> disbandTeam(
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId,
            @Parameter(description = "해체할 팀 ID") @PathVariable UUID teamId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        teamService.disbandTeam(teamId, orgId, currentUserResolver.resolveCurrentMemberId());
        return ResponseEntity.noContent().build();
    }
}