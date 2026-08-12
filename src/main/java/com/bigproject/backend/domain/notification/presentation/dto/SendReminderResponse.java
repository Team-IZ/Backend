package com.bigproject.backend.domain.notification.presentation.dto;

import java.util.List;
import java.util.UUID;

public record SendReminderResponse(UUID dispatchBatchId, List<Dispatch> dispatches) {
	public record Dispatch(UUID dispatchId, UUID traineeId, String status) { }
}
