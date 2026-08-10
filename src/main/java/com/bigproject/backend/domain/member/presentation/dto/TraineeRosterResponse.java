package com.bigproject.backend.domain.member.presentation.dto;

import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.InactivationReasonCode;
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
		int unassignedCount,
		@Schema(description = """
				기수 전체 교육생 수이며 **필터를 적용하지 않은 모집단**이라 totalElements와 다릅니다.
				화면 상단의 '명단 393명'과 검색 결과가 없을 때의 '7기 393명에서 찾았습니다'가 이 값이며,
				둘 다 필터와 무관하게 같은 수를 보여줘야 해서 목록 한 페이지로는 만들 수 없습니다.
				unassignedCount와 같은 모집단이라 '393명 중 미배정 12'가 그대로 성립합니다.
				""", example = "393")
		int cohortTotal
) {

	/**
	 * springdoc은 스키마를 <b>단순 클래스 이름</b>으로 키잉하므로 이름을 명시하지 않으면
	 * {@code RegisterTraineesRequest.Trainee}(이름·이메일 2개짜리 요청 DTO)와 같은 {@code Trainee} 키를
	 * 놓고 충돌해 <b>먼저 등록된 쪽이 이긴다</b>. 실제로 이 목록이 {@code {name, email}}만 돌려준다고
	 * 선언되어 traineeId·status·소속 반이 생성 타입에서 통째로 사라졌다.
	 */
	@Schema(name = "TraineeRosterEntry", description = "교육생 명단 한 행")
	public record Trainee(
			@Schema(description = "교육생 사용자 ID. 상태 변경 시 이 값을 경로에 쓴다") UUID traineeId,
			String name,
			String email,
			@Schema(description = "계정 상태. INVITED(초대 대기)는 아직 활성화 전이라 상태를 직접 바꿀 수 없다") AccountStatus status,
			@Schema(description = "현재 소속 반 ID. 반 배정이 없으면 null", nullable = true) UUID classroomId,
			@Schema(description = "현재 소속 반 이름. 반 배정이 없으면 null", nullable = true) String className,
			@Schema(description = "기수 등록일") OffsetDateTime joinedAt,
			@Schema(description = "중도 이탈일. 이탈하지 않았으면 null", nullable = true) OffsetDateTime leftAt,
			@Schema(description = """
					계정 비활성화 사유 코드입니다. RESIGNED(퇴사) · ADMIN_SUSPENDED(운영자 조치) ·
					CONTRACT_ENDED(계약 종료) · SECURITY_ACTION(보안 조치) · OTHER(기타).
					계정이 INACTIVE일 때만 값이 있으며 그때는 **항상 채워져 있습니다** —
					ck_app_user_status_3이 INACTIVE인 행에 이 값을 NOT NULL로 강제하기 때문입니다.

					11차 R6 — 타입이 `string`에서 **enum**으로 바뀌었습니다. 다섯 값은 DB의
					`ck_app_user_inactivated_reason_code`가 못 박고 있는 것과 같습니다.
					""", example = "ADMIN_SUSPENDED", nullable = true)
			InactivationReasonCode inactivatedReasonCode,
			@Schema(description = """
					비활성화 상세 사유이며 상태 변경 요청의 reason이 그대로 들어갑니다.
					사유 코드와 달리 **INACTIVE여도 null일 수 있습니다**(요청에서 생략 가능).
					""", example = "중도 이탈 처리", nullable = true)
			String inactivatedReason,
			@Schema(description = """
					계정을 비활성화한 사용자 ID이며 계정이 INACTIVE일 때만 값이 있습니다.
					ck_app_user_status_3이 INACTIVE인 행에 이 값을 NOT NULL로 강제하므로 그때는 항상 채워집니다.
					""", nullable = true)
			UUID inactivatedById,
			@Schema(description = """
					계정을 비활성화한 사용자의 이름이며 화면에 그대로 표시하는 값입니다.
					`inactivatedById`가 가리키는 계정에서 읽어 오고, 활성이면 null입니다.
					""", example = "김오퍼레이터", nullable = true)
			String inactivatedByName,
			@Schema(description = "계정이 비활성화된 시각. 활성이면 null", nullable = true)
			OffsetDateTime inactivatedAt,

			@Schema(description = """
					아직 수락·취소되지 않은 초대 토큰입니다(11차 R2). `null`이 아닐 때만 **재발송 버튼을 켭니다** —
					매니저·오퍼레이터 목록의 `pendingInvitationTokenId`와 같은 규칙입니다.

					예전에는 이 값이 없어 화면이 `status === 'INVITED'`로 유추해야 했습니다.
					이미 활성화됐거나 초대가 취소된 계정은 넘길 토큰이 없어 `null`입니다.
					""", nullable = true)
			UUID pendingInvitationTokenId
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
					row.leftAt(),
					InactivationReasonCode.from(row.inactivatedReasonCode()),
					row.inactivatedReason(),
					row.inactivatedById(),
					row.inactivatedByName(),
					row.inactivatedAt(),
					row.pendingInvitationTokenId()
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
