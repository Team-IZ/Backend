package com.bigproject.backend.domain.member.presentation.dto;

import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.TraineeRosterRepository;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "기수 교육생 명단 페이지 응답")
public record TraineeRosterResponse(
		@Schema(description = "이 페이지의 교육생 목록") List<Trainee> content,
		@Schema(description = "0부터 시작하는 현재 페이지 번호", example = "0") int page,
		@Schema(description = "페이지당 개수", example = "20") int size,
		@Schema(description = "필터 적용 후 전체 교육생 수") long totalElements,
		@Schema(description = "필터 적용 후 전체 페이지 수") int totalPages,
		@Schema(description = "반 배정이 없는 교육생 수. 필터와 무관하게 기수 전체 기준(화면 상단 '미배정 N')")
		int unassignedCount
) {

	@Schema(description = "교육생 명단 한 행")
	public record Trainee(
			@Schema(description = "교육생 사용자 ID. 상태 변경 시 이 값을 경로에 쓴다") UUID traineeId,
			String name,
			String email,
			@Schema(description = "계정 상태. INVITED(초대 대기)는 아직 활성화 전이라 상태를 직접 바꿀 수 없다") AccountStatus status,
			@Schema(description = "현재 소속 반 ID. 반 배정이 없으면 null", nullable = true) UUID classroomId,
			@Schema(description = "현재 소속 반 이름. 반 배정이 없으면 null", nullable = true) String className,
			@Schema(description = "기수 등록일") OffsetDateTime joinedAt,
			@Schema(description = "중도 이탈일. 이탈하지 않았으면 null", nullable = true) OffsetDateTime leftAt
	) {
		public static Trainee from(TraineeRosterRepository.RosterRow row) {
			return new Trainee(
					row.traineeId(),
					row.name(),
					row.email(),
					toAccountStatus(row.rawAccountStatus()),
					row.classroomId(),
					row.className(),
					row.joinedAt(),
					row.leftAt()
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
