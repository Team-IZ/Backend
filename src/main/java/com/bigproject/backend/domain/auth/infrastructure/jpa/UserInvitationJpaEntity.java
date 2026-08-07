package com.bigproject.backend.domain.auth.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "user_invitation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserInvitationJpaEntity {
	@Id
	@Column(name = "invitation_id", nullable = false, updatable = false)
	private UUID invitationId;
}
