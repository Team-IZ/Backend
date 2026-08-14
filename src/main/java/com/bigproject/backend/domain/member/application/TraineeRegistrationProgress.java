package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.member.domain.MemberInvitationRepository;

/**
 * 일괄 등록 한 건의 진행률.
 *
 * <p><b>잡 상태를 저장하지 않고 유도한다.</b> 저장하면 행 상태와 잡 상태가 어긋날 수 있는데(발송은
 * 끝났는데 잡은 RUNNING인 채 남는 식), 집계에서 유도하면 어긋날 여지가 없다. 배포본이 셋이라
 * 인메모리 진행률은 애초에 성립하지 않는다는 제약도 같은 답으로 이어진다 — 어느 인스턴스가 폴링을
 * 받아도 같은 원장을 집계해 같은 답을 낸다.
 */
public record TraineeRegistrationProgress(
		int registeredCount,
		int invitationSentCount,
		int mailFailedCount,
		int mailPendingCount,
		Status status
) {
	/** 잡 상태. 저장된 값이 아니라 위 네 숫자에서 나온다. */
	public enum Status {
		/** 아직 보내지 않은 초대가 남아 있다. 화면은 계속 폴링한다. */
		RUNNING,
		/** 발송이 끝났고 그중 일부가 나가지 못했다. 화면은 [초대 재발송]을 안내한다. */
		PARTIAL,
		/** 발송이 전부 끝났고 실패가 없다. */
		SUCCEEDED
	}

	public static TraineeRegistrationProgress from(MemberInvitationRepository.BatchProgress progress) {
		return new TraineeRegistrationProgress(
				progress.registeredCount(),
				progress.invitationSentCount(),
				progress.mailFailedCount(),
				progress.mailPendingCount(),
				statusOf(progress)
		);
	}

	private static Status statusOf(MemberInvitationRepository.BatchProgress progress) {
		if (progress.mailPendingCount() > 0) {
			return Status.RUNNING;
		}
		return progress.mailFailedCount() > 0 ? Status.PARTIAL : Status.SUCCEEDED;
	}
}
