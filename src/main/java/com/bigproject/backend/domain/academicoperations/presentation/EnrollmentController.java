package com.bigproject.backend.domain.academicoperations.presentation;

import com.bigproject.backend.domain.academicoperations.domain.AcademicOperationsErrorCode;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.domain.academicoperations.application.CohortService;
import com.bigproject.backend.domain.academicoperations.presentation.dto.EnrollmentListResponse;
import com.bigproject.backend.domain.academicoperations.presentation.dto.EnrollmentResponse;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

// TR-01 홈 > 미제출(기한 내) 화면의 최초 진입점.
// 경로 접두사는 /members(member 도메인 소유)와 겹치지만, 실제 소유 도메인은 academicoperations라서
// TraineeController가 /cohorts/{cohortId}/trainees를 별도 컨트롤러로 두는 것과 같은 방식으로 분리했다.
@Tag(name = "Academic Operations")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping(value = "/members/me", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class EnrollmentController {

    private final CohortService cohortService;
    private final CurrentUserResolver currentUserResolver;

    @Operation(
            operationId = "findMyEnrollments",
            summary = "내 소속 기수·반 조회 | ✅ 사용 가능",
            description = """
					로그인한 사용자가 현재 유효하게(LEFT 아닌) 소속된 기수와, 기수별 현재 반 배정을 조회한다.
					조회 범위인 기관은 액세스 토큰에서 가져오므로 다른 기관 소속은 조회되지 않는다.

					**요청**
					- 파라미터 없음(토큰의 사용자 본인 기준)

					**응답 (200)**
					- enrollments[].cohortId: 기수 ID
					- enrollments[].cohortName: 기수명
					- enrollments[].classroom: 현재 배정된 반. 아직 배정 전이면 null
					- enrollments[].classroom.classroomId / name: 반 ID·이름

					**아직 채워지지 않는 값** — 반 배정 전이면 classroom은 null이다. 오퍼레이터가
					`PATCH /cohorts/{cohortId}/classrooms/trainee-assignments`를 실행하기 전 구간에서
					정상적으로 발생하는 상태다.
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공(소속이 없으면 빈 배열)"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "500", description = "ORGANIZATION_CONTEXT_MISSING 인증 정보에서 organizationId를 확인할 수 없음")
    })
    @GetMapping("/enrollments")
    public ResponseEntity<EnrollmentListResponse> findMyEnrollments(Authentication authentication) {
        UUID organizationId = extractOrganizationId(authentication);
        UUID userId = currentUserResolver.resolveCurrentMemberId();

        List<EnrollmentResponse> enrollments = cohortService.findMyEnrollments(userId, organizationId).stream()
                .map(EnrollmentResponse::from)
                .toList();

        return ResponseEntity.ok(new EnrollmentListResponse(enrollments));
    }

    private UUID extractOrganizationId(Authentication authentication) {
        Object details = authentication.getDetails();
        if (!(details instanceof UUID organizationId)) {
            throw new ApiException(AcademicOperationsErrorCode.ORGANIZATION_CONTEXT_MISSING);
        }
        return organizationId;
    }
}