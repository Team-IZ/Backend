package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.academicoperations.application.ClassroomService;
import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.ManagerAccountRepository;
import com.bigproject.backend.domain.member.domain.ManagerRosterRepository;
import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * 매니저 계정 조작 3종 — 정지·재활성 / 초대 재발송 / 초대 취소(9차 R7).
 *
 * <p>오퍼레이터 쪽 {@code OperatorServiceImpl}과 <b>같은 모양</b>으로 만들었다. 계정 원장·토큰·초대 원장이
 * 같은 테이블이라 규칙이 갈리면 같은 사실이 역할별로 다르게 처리된다.
 *
 * <p>오퍼레이터와 다른 점이 하나 있다 — <b>정지할 때 담당 반을 함께 놓는다.</b> 오퍼레이터는 반을
 * 담당하지 않지만 매니저는 담당하므로, 상태만 바꾸면 그만둔 사람이 반을 붙들고 있어
 * `담당 매니저 없음` 경고에 잡히지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ManagerAccountService {

	/**
	 * 토큰 무효화 사유. {@code one_time_token.invalidated_reason}은 자유 문장이 아니라 <b>코드값 컬럼</b>이다 —
	 * {@code ck_one_time_token_invalidated_reason}이 정해진 값만 허용하므로 설명 문장을 넣으면 UPDATE가 실패한다.
	 */
	private static final String CANCEL_REASON_CODE = "INVITATION_CANCELLED";

	/** 계정 정지 이력에 남길 사람이 읽을 설명. 코드 컬럼이 아니라 자유 텍스트 컬럼에 들어간다. */
	private static final String CANCEL_ACCOUNT_REASON = "오퍼레이터가 매니저 초대를 취소했습니다.";

	/** {@code app_user.inactivated_reason_code} 코드값. 화면에서 오는 정지는 전부 관리자 조치다. */
	private static final String ADMIN_SUSPENDED = "ADMIN_SUSPENDED";

	private final ManagerAccountRepository managerAccountRepository;
	private final ManagerRosterRepository managerRosterRepository;
	private final MemberInvitationService memberInvitationService;
	private final ClassroomService classroomService;
	private final CurrentUserResolver currentUserResolver;

	/**
	 * 정지 / 재활성.
	 *
	 * <p>INACTIVE로 내릴 때 담당 반을 <b>같은 트랜잭션에서</b> 해제한다. 화면이 반마다
	 * {@code PATCH …/managers}를 따로 부르면 중간에 실패했을 때 절반만 풀리고, 되돌릴 방법이 없다.
	 *
	 * <p><b>재활성해도 반은 돌려주지 않는다.</b> 그 사이 다른 사람이 맡았을 수 있고, 되돌릴 것은
	 * 배정이지 상태가 아니다.
	 */
	@Transactional
	public ManagerRosterRepository.ManagerRosterRow updateManagerStatus(
			UUID organizationId, UUID managerId, AccountStatus status, String reason) {

		ManagerAccountRepository.ManagerAccount target = managerAccountRepository
				.findManager(organizationId, managerId)
				.orElseThrow(() -> new ApiException(
						MemberErrorCode.MANAGER_NOT_FOUND, "이 기관의 매니저 계정을 찾을 수 없습니다: " + managerId));

		String rawStatus = toRawStatus(status);
		if (status == AccountStatus.INACTIVE) {
			assertNotLastActiveManager(organizationId, target);
		}

		UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
		// 이미 그 상태면 다시 쓰지 않는다 — 정지 시각·정지자가 조작할 때마다 덮어써지면 이력이 흐려진다.
		if (!rawStatus.equals(target.rawStatus())) {
			managerAccountRepository.updateManagerStatus(
					managerId, rawStatus, actorUserId, ADMIN_SUSPENDED, reason);
		}

		// 상태가 이미 INACTIVE였더라도 담당이 남아 있을 수 있어 정지 요청에는 항상 해제를 태운다.
		if (status == AccountStatus.INACTIVE) {
			classroomService.releaseAllAssignmentsForManager(managerId, organizationId, actorUserId);
		}

		return reload(organizationId, managerId);
	}

	/**
	 * 초대 취소. 오퍼레이터 취소와 같은 세 가지가 함께 일어난다 —
	 * 토큰 무효화 · 초대 원장 CANCELLED · 계정 자리는 남기고 INACTIVE.
	 *
	 * <p>계정을 INACTIVE로 내리는 것은 <b>재초대의 전제</b>다. {@code app_user.normalized_email}이 UNIQUE라
	 * 재초대는 새 계정을 만들지 못하고 기존 자리를 되살려 쓰는데, 그 자리를 찾는 조회가
	 * {@code status='INACTIVE'}를 본다.
	 */
	@Transactional
	public ManagerRosterRepository.ManagerRosterRow cancelInvitation(UUID organizationId, UUID tokenId) {
		ManagerAccountRepository.PendingManagerInvitation invitation = managerAccountRepository
				.findPendingInvitation(organizationId, tokenId)
				.orElseThrow(() -> new ApiException(
						MemberErrorCode.MANAGER_INVITATION_NOT_FOUND,
						"취소할 수 있는 매니저 초대를 찾을 수 없습니다: " + tokenId));

		UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
		managerAccountRepository.invalidateInvitation(tokenId, CANCEL_REASON_CODE);

		// 토큰만 무효화하면 초대 원장이 SENT로 남아 (기관, 이메일, 역할) 조합을 계속 점유한다 —
		// 그러면 같은 주소로 재초대할 수 없다.
		if (invitation.invitationId() != null) {
			managerAccountRepository.cancelInvitationLedger(invitation.invitationId(), actorUserId);
		}

		if (invitation.memberId() != null) {
			managerAccountRepository.updateManagerStatus(
					invitation.memberId(), "INACTIVE", actorUserId, ADMIN_SUSPENDED, CANCEL_ACCOUNT_REASON);
			// 수락 전 계정이라 담당 반이 있을 수 없지만, 상태와 담당이 함께 움직인다는 규칙을 한 곳에서만
			// 어기지 않도록 정지와 같은 처리를 태운다.
			classroomService.releaseAllAssignmentsForManager(invitation.memberId(), organizationId, actorUserId);
		}

		return reload(organizationId, invitation.memberId());
	}

	/**
	 * 초대 재발송. 발송 자체는 {@code MemberInvitationService.resendInvitation}이 이미 하고 있다
	 * (이전 토큰 무효화 + 새 토큰 발급, 초대 원장은 새로 만들지 않아 재발송 횟수가 정확히 쌓인다).
	 *
	 * <p>여기서 하는 일은 <b>기관·역할 경계를 세우는 것</b>이다. member 도메인은 역할 축(누가 재발송할 수
	 * 있는가)만 검증하므로, 다른 기관 토큰 ID를 넣어 남의 초대를 건드리는 경로를 이 조회가 막는다.
	 */
	@Transactional
	public ManagerRosterRepository.ManagerRosterRow resendInvitation(
			UUID organizationId, UUID tokenId, String actorEmail, String requestId) {

		ManagerAccountRepository.PendingManagerInvitation invitation = managerAccountRepository
				.findPendingInvitation(organizationId, tokenId)
				.orElseThrow(() -> new ApiException(
						MemberErrorCode.MANAGER_INVITATION_NOT_FOUND,
						"재발송할 수 있는 매니저 초대를 찾을 수 없습니다: " + tokenId));

		try {
			memberInvitationService.resendInvitation(tokenId, actorEmail, requestId);
		} catch (ResponseStatusException exception) {
			// member 도메인은 메일 발송 실패를 502로 올린다. 사용자가 할 일은 [재발송] 하나라 한 코드로 합친다.
			if (exception.getStatusCode() == HttpStatus.BAD_GATEWAY) {
				throw new ApiException(
						MemberErrorCode.INVITE_MAIL_FAILED, MemberErrorCode.INVITE_MAIL_FAILED.defaultMessage(), exception);
			}
			throw exception;
		}

		return reload(organizationId, invitation.memberId());
	}

	/**
	 * 매니저 한 명의 담당 반을 한 번에 저장한다(9차 Q3-①).
	 *
	 * <p>매니저 상세 모달이 담당 반 셋을 체크하면 반 기준 API로는 {@code PATCH}를 반 수만큼 나눠 불러야 하고,
	 * 중간에 하나가 실패하면 <b>절반만 반영된 상태</b>로 남는다. 이 경로는 한 트랜잭션이라 그 상태가 없다.
	 *
	 * <p>대상이 이 기관의 매니저인지 먼저 확인한다 — 반 배정 자체는 사용자 역할을 보지 않으므로
	 * ({@code PATCH …/managers}의 알려진 한계) 여기서 세워 두지 않으면 아무 사용자 ID나 담당이 될 수 있다.
	 */
	@Transactional
	public ManagerRosterRepository.ManagerRosterRow replaceClassrooms(
			UUID organizationId, UUID managerId, List<UUID> classroomIds) {

		managerAccountRepository.findManager(organizationId, managerId)
				.orElseThrow(() -> new ApiException(
						MemberErrorCode.MANAGER_NOT_FOUND, "이 기관의 매니저 계정을 찾을 수 없습니다: " + managerId));

		classroomService.replaceManagerClassrooms(
				organizationId, managerId, classroomIds, currentUserResolver.resolveCurrentMemberId());

		return reload(organizationId, managerId);
	}

	/** 조작 대상의 기관 전체 활성 매니저 수. 화면이 {@code suspendable}을 그리는 데 쓰는 값과 같은 기준이다. */
	public int countActiveManagers(UUID organizationId) {
		return managerRosterRepository.countActiveManagers(organizationId);
	}

	/**
	 * 이미 활성이 아닌 계정을 정지하는 것은 활성 수를 줄이지 않으므로 차단 대상이 아니다.
	 * {@code OperatorServiceImpl.assertNotLastActiveOperator}와 같은 판정이다.
	 */
	private void assertNotLastActiveManager(UUID organizationId, ManagerAccountRepository.ManagerAccount target) {
		if (!"ACTIVE".equals(target.rawStatus())) {
			return;
		}
		if (managerRosterRepository.countActiveManagers(organizationId) <= 1) {
			throw new ApiException(
					MemberErrorCode.LAST_MANAGER,
					"이 기관의 마지막 활성 매니저입니다. 정지하면 담당 매니저가 한 명도 남지 않아 반 학생의 면담·독촉을 "
							+ "아무도 처리할 수 없습니다. 새 매니저를 먼저 초대해 활성화한 뒤에 정지할 수 있습니다.");
		}
	}

	/** 조작 후의 그 행을 목록과 <b>같은 SELECT</b>로 다시 읽는다 — 두 응답의 필드가 어긋날 수 없다. */
	private ManagerRosterRepository.ManagerRosterRow reload(UUID organizationId, UUID managerId) {
		if (managerId == null) {
			// 초대 토큰에 계정 자리가 없는 경우. 초대 흐름이 항상 자리를 먼저 만들므로 정상 경로에서는 오지 않는다.
			throw new ApiException(MemberErrorCode.MANAGER_INVITATION_NOT_FOUND, "초대에 연결된 매니저 계정이 없습니다.");
		}
		return managerRosterRepository.findManager(organizationId, managerId)
				.orElseThrow(() -> new ApiException(
						MemberErrorCode.MANAGER_NOT_FOUND, "이 기관의 매니저 계정을 찾을 수 없습니다: " + managerId));
	}

	private String toRawStatus(AccountStatus status) {
		return switch (status) {
			case ACTIVE -> "ACTIVE";
			case INACTIVE -> "INACTIVE";
			// 컨트롤러의 @AssertTrue가 먼저 막지만, 이 메서드가 다른 경로에서 불릴 때를 대비해 한 번 더 닫는다.
			case INVITED -> throw new ApiException(
					MemberErrorCode.ACCOUNT_STATUS_FILTER_NOT_SUPPORTED,
					"매니저 계정 상태는 ACTIVE 또는 INACTIVE로만 바꿀 수 있습니다.");
		};
	}
}
