package com.bigproject.backend.domain.member.presentation.dto;

import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.ManagerRosterRepository;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Schema(description = "기관 매니저 목록 페이지 응답")
public record ManagerRosterResponse(
		@Schema(description = "이 페이지의 매니저 목록") List<Manager> content,
		@Schema(description = "0부터 시작하는 현재 페이지 번호", example = "0") int page,
		@Schema(description = "페이지당 개수", example = "20") int size,
		@Schema(description = "필터 적용 후 전체 매니저 수") long totalElements,
		@Schema(description = "필터 적용 후 전체 페이지 수") int totalPages,
		@Schema(description = """
				계정 상태별 매니저 수이며 **필터를 적용하지 않은 기관 전체 모집단**이라 totalElements와 다릅니다.
				화면 상단의 '매니저 9명 · 활성 7 · 초대 대기 1 · 정지 1'이 이 값이며, 상태 칩이 자기 자신을
				필터링하면 안 되므로 목록 한 페이지로는 만들 수 없습니다.
				INVITED · ACTIVE · INACTIVE 세 키가 **항상 모두 있고**, 0명인 상태는 0으로 옵니다 —
				키가 빠지는 것과 0명인 것은 다릅니다. 세 값을 더하면 기관 전체 매니저 수입니다.
				""", example = "{\"INVITED\": 1, \"ACTIVE\": 7, \"INACTIVE\": 1}")
		Map<AccountStatus, Long> statusCounts
) {

	/**
	 * springdoc은 스키마를 <b>단순 클래스 이름</b>으로 키잉하므로 이름을 명시하지 않으면
	 * {@code ClassroomResponse.Manager}·{@code CohortResponse.Manager}(담당자 요약 2개짜리)와 같은
	 * {@code Manager} 키를 놓고 충돌해 <b>먼저 등록된 쪽이 이긴다</b>. 실제로 이 목록이
	 * {@code {memberId, name}}만 돌려준다고 선언되어 상태·담당 반·담당 인원이 생성 타입에서 사라졌다.
	 */
	@Schema(name = "ManagerRosterEntry", description = "매니저 한 행")
	public record Manager(
			@Schema(description = "매니저 사용자 ID") UUID managerId,
			@Schema(description = "이름. 초대만 되고 아직 활성화되지 않으면 비어 있다(화면에서는 `—`)", nullable = true)
			String name,
			String email,
			@Schema(description = "계정 상태. INVITED(초대 대기) / ACTIVE(활성) / INACTIVE(정지)") AccountStatus status,
			@Schema(description = "담당할 기수 ID. 가장 최근 매니저 초대의 target_cohort_id 기준", nullable = true)
			UUID cohortId,
			@Schema(description = "담당할 기수명", nullable = true) String cohortName,
			@Schema(description = "현재 담당 반 이름 목록. 담당이 없으면 빈 배열(화면에서는 `미배정`)")
			List<String> classroomNames,
			@Schema(description = "담당 반들에 소속된 재학(ACTIVE) 교육생 합계. 담당이 없으면 0", example = "50")
			long assignedTraineeCount,
			@Schema(description = "최근 로그인 시각. 한 번도 로그인하지 않았으면 null(화면에서는 `—`)", nullable = true)
			Instant lastLoginAt,
			@Schema(description = "최초 초대 시각. 초대 이력이 없으면 null", nullable = true)
			Instant invitedAt,
			@Schema(description = """
					초대한 사람의 이름이며 `invitedAt`과 **같은 초대 행**에서 읽습니다.
					화면 비고의 '2026-07-24 초대 · 김오퍼레이터'에서 뒷부분이 이 값이라, 날짜와 사람이
					서로 다른 초대에서 오면 안 됩니다. 초대 이력이 없거나 초대한 계정이 지워졌으면 null입니다.
					""", example = "김오퍼레이터", nullable = true)
			String invitedByName
	) {
		public static Manager from(ManagerRosterRepository.ManagerRosterRow row) {
			return new Manager(
					row.managerId(),
					row.name(),
					row.email(),
					toAccountStatus(row.rawAccountStatus()),
					row.cohortId(),
					row.cohortName(),
					row.classroomNames(),
					row.assignedTraineeCount(),
					row.lastLoginAt(),
					row.invitedAt(),
					row.invitedByName()
			);
		}

		private static AccountStatus toAccountStatus(String rawStatus) {
			return switch (rawStatus) {
				case "PENDING" -> AccountStatus.INVITED;
				case "ACTIVE" -> AccountStatus.ACTIVE;
				case "INACTIVE" -> AccountStatus.INACTIVE;
				default -> throw new IllegalStateException("알 수 없는 app_user.status 값입니다: " + rawStatus);
			};
		}
	}
}
