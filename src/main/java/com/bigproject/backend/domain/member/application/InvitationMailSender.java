package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.member.domain.PendingInvitation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface InvitationMailSender {
	/** 슈퍼어드민 초대. 기관이 없어 매니저 초대 본문의 "○○ 기관의" 문구를 쓸 수 없다. */
	void sendSuperAdminInvitation(PendingInvitation invitation);

	void sendManagerInvitation(PendingInvitation invitation);

	void sendTraineeInvitation(PendingInvitation invitation, String traineeName);

	/**
	 * 여러 교육생 초대를 <b>SMTP 연결 하나로</b> 보낸다.
	 *
	 * <p>{@link #sendTraineeInvitation}을 인원수만큼 부르면 매번 TCP 연결 → STARTTLS 핸드셰이크 →
	 * AUTH → QUIT을 반복한다. 그 고정비가 건당 1초 이상이라 CSV 대량 등록에서 전체 소요의 대부분을
	 * 차지했다 — 900명이면 약 28분이다. 연결을 재사용하면 고정비를 청크당 한 번만 낸다.
	 *
	 * <p><b>한 건이 실패해도 나머지를 계속 보낸다.</b> 예외를 던지지 않고 실패한 초대만 돌려준다 —
	 * 500번째에서 예외가 나면 앞의 499건이 이미 만들어졌는데도 호출부가 전량 실패로 처리하게 된다.
	 *
	 * @return 발송하지 못한 초대의 {@code tokenId} → 실패 사유. 전부 성공하면 빈 맵
	 */
	Map<UUID, String> sendTraineeInvitations(List<TraineeInvitationMail> mails);

	/** 발송 대상 한 건. 이름은 본문 인사말에 쓰이며 {@link PendingInvitation}에는 없다. */
	record TraineeInvitationMail(PendingInvitation invitation, String traineeName) {
	}
}
