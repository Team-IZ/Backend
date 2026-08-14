package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.member.domain.MemberInvitationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 발송이 멈춘 교육생 초대를 이어받는 안전망.
 *
 * <p>정상 경로에서는 자리를 확보한 인스턴스가 {@link AsyncTraineeInvitationMailer}로 곧바로 발송하므로
 * 여기 걸리는 것이 <b>0건인 것이 정상</b>이다. 배포·크래시로 그 발송이 끊기거나 발송 큐가 차서 거절되면
 * 초대가 {@code PENDING}인 채 남는데, 그 행들을 다음 주기에 회수한다.
 *
 * <h2>클레임이 필수인 이유</h2>
 *
 * <p>배포본이 셋이고 같은 운영 DB에 붙어 있다. PENDING을 그냥 조회해 보내면 세 인스턴스가 같은 초대를
 * 집어 <b>교육생에게 메일이 3통</b> 간다. {@code FOR UPDATE SKIP LOCKED}로 한 인스턴스만 집게 하고,
 * 집은 시각({@code mail_claimed_at})을 찍어 다음 주기가 그것을 건너뛰게 한다.
 *
 * <p>그 시각이 곧 하트비트다 — 발송 중 인스턴스가 죽으면 값이 갱신되지 않은 채 남아 스톨로 드러난다.
 * 별도 복구 잡도, 별도 컬럼도 필요 없다.
 *
 * <h2>중복 발송보다 유실을 막는다</h2>
 *
 * <p>메일이 나간 뒤 기록 직전에 죽으면 그 초대는 다음 주기에 다시 나간다. 교육생이 같은 초대를 두 번
 * 받는 쪽이, 초대를 영영 못 받는 쪽보다 낫다고 본다. 이전 토큰은 무효화되므로 유효한 링크는 늘 하나다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TraineeInvitationOutbox {
	private final MemberInvitationRepository invitationRepository;
	private final InvitationPersistenceService persistenceService;
	private final TransactionalInvitationDispatcher invitationDispatcher;

	/**
	 * 이 시간이 지나도 PENDING인 클레임을 스톨로 본다.
	 *
	 * <p><b>정상 발송이 끝나는 데 걸리는 시간보다 넉넉해야 한다.</b> 짧게 잡으면 아직 보내는 중인 초대를
	 * 다른 인스턴스가 집어 중복 발송이 된다. 900명이 연결당 100건씩 약 3분이라 그 서너 배로 잡았다.
	 */
	@Value("${invitation.mail.outbox.stall-threshold:PT10M}")
	private Duration stallThreshold;

	/** 한 주기에 집을 최대 건수. 크게 잡으면 한 인스턴스가 오래 붙들고, 작게 잡으면 회수가 느려진다. */
	@Value("${invitation.mail.outbox.claim-limit:200}")
	private int claimLimit;

	/**
	 * @return 이번 주기에 이어받아 발송을 시도한 초대 수
	 */
	public int dispatchStalledInvitations() {
		Instant now = Instant.now();
		List<MemberInvitationRepository.StalledInvitation> claimed =
				invitationRepository.claimStalledTraineeInvitations(now, now.minus(stallThreshold), claimLimit);
		if (claimed.isEmpty()) {
			return 0;
		}

		log.info("멈춰 있던 교육생 초대를 이어받는다: count={}", claimed.size());
		int dispatched = 0;
		/*
		 * 기관별로 나눈다. 이전 토큰 무효화가 기관 단위 질의라 섞어서 부를 수 없고,
		 * 한 기관이 실패해도 다른 기관의 회수까지 멈추지 않는다.
		 */
		for (Map.Entry<UUID, List<MemberInvitationRepository.StalledInvitation>> group
				: groupByOrganization(claimed).entrySet()) {
			try {
				List<InvitationMailSender.TraineeInvitationMail> mails =
						persistenceService.recreateTraineeInvitationTokens(group.getKey(), group.getValue());
				invitationDispatcher.sendTraineeInvitations(mails);
				dispatched += mails.size();
			} catch (RuntimeException exception) {
				/*
				 * 이 기관 분량은 PENDING인 채 남는다. 클레임 시각은 갱신됐으므로 스톨 기준을 다시 넘길 때까지
				 * 기다렸다가 재시도한다 — 실패가 계속되면 같은 주기 로그가 반복해서 남아 신호가 된다.
				 */
				log.error("멈춰 있던 교육생 초대를 이어받지 못했다: organizationId={}, count={}",
						group.getKey(), group.getValue().size(), exception);
			}
		}
		return dispatched;
	}

	private Map<UUID, List<MemberInvitationRepository.StalledInvitation>> groupByOrganization(
			List<MemberInvitationRepository.StalledInvitation> invitations
	) {
		Map<UUID, List<MemberInvitationRepository.StalledInvitation>> grouped = new LinkedHashMap<>();
		for (MemberInvitationRepository.StalledInvitation invitation : invitations) {
			grouped.computeIfAbsent(invitation.context().organizationId(), key -> new ArrayList<>())
					.add(invitation);
		}
		return grouped;
	}
}
