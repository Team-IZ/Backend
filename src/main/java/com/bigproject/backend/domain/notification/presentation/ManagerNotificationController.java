package com.bigproject.backend.domain.notification.presentation;

import com.bigproject.backend.domain.notification.application.ManagerNotificationService;
import com.bigproject.backend.domain.notification.presentation.dto.NotificationInboxResponse;
import com.bigproject.backend.domain.notification.presentation.dto.SendReminderRequest;
import com.bigproject.backend.domain.notification.presentation.dto.SendReminderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Tag(name = "Notification", description = "매니저 인박스와 독촉 발송")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RestController
@RequestMapping(value = "/cohorts/{cohortId}/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('MANAGER')")
@RequiredArgsConstructor
public class ManagerNotificationController {
	private final ManagerNotificationService service;

	@Operation(operationId = "findManagerNotificationInbox", summary = "매니저 인박스 조회 | ⚠️ 사용 불가")
	@GetMapping("/inbox")
	public ResponseEntity<NotificationInboxResponse> findInbox(
			@PathVariable UUID cohortId,
			@RequestParam(required = false) UUID projectId,
			@RequestParam(required = false) UUID assessmentRoundId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime since,
			@RequestParam(defaultValue = "false") boolean includeResolved,
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
			Authentication authentication) {
		return ResponseEntity.ok(service.findInbox(authentication.getName(), cohortId, projectId,
				assessmentRoundId, since, includeResolved, cursor, size));
	}

	@Operation(operationId = "sendManagerReminder", summary = "매니저 단건 독촉 발송 | ⚠️ 사용 불가")
	@PostMapping(value = "/reminders", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<SendReminderResponse> sendReminder(
			@PathVariable UUID cohortId,
			@RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
			@Valid @RequestBody SendReminderRequest request,
			Authentication authentication) {
		return ResponseEntity.ok(service.sendReminder(authentication.getName(), cohortId, idempotencyKey, request));
	}
}
