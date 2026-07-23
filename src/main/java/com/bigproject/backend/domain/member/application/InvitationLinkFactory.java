package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.member.domain.PendingInvitation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class InvitationLinkFactory {
	private final String baseUrl;
	private final String managerPath;
	private final String traineePath;

	public InvitationLinkFactory(
			@Value("${invitation.base-url:http://localhost:5173}") String baseUrl,
			@Value("${invitation.manager-path:/manager/signup}") String managerPath,
			@Value("${invitation.trainee-path:/trainee/activation}") String traineePath
	) {
		this.baseUrl = baseUrl;
		this.managerPath = managerPath;
		this.traineePath = traineePath;
	}

	public String managerLink(PendingInvitation invitation) {
		return create(managerPath, invitation.rawToken());
	}

	public String traineeLink(PendingInvitation invitation) {
		return create(traineePath, invitation.rawToken());
	}

	private String create(String path, String token) {
		return UriComponentsBuilder.fromUriString(baseUrl)
				.path(path)
				.queryParam("token", token)
				.build()
				.encode()
				.toUriString();
	}
}
