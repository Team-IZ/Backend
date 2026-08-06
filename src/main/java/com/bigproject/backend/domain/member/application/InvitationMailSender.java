package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.member.domain.PendingInvitation;

public interface InvitationMailSender {
	/** 슈퍼어드민 초대. 기관이 없어 매니저 초대 본문의 "○○ 기관의" 문구를 쓸 수 없다. */
	void sendSuperAdminInvitation(PendingInvitation invitation);

	void sendManagerInvitation(PendingInvitation invitation);

	void sendTraineeInvitation(PendingInvitation invitation, String traineeName);
}
