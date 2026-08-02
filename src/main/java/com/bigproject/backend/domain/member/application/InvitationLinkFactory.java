package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.member.domain.PendingInvitation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class InvitationLinkFactory {
	private final String baseUrl;

	public InvitationLinkFactory(
			@Value("${invitation.base-url:http://localhost:5173}") String baseUrl
	) {
		this.baseUrl = baseUrl;
	}

	public String managerLink(PendingInvitation invitation) {
		return create("op-", invitation.rawToken());
	}

	public String traineeLink(PendingInvitation invitation) {
		return create("stu-", invitation.rawToken());
	}

	private String create(String tokenPrefix, String token) {
		return UriComponentsBuilder.fromUriString(baseUrl)
				.pathSegment("invite", tokenPrefix + token)
				.build()
				.encode()
				.toUriString();
	}
}
