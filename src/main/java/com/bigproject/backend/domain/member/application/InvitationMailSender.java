package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.member.domain.PendingInvitation;

public interface InvitationMailSender {
	void sendManagerInvitation(PendingInvitation invitation);

	void sendTraineeInvitation(PendingInvitation invitation, String traineeName);
}
