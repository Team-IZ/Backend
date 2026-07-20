package com.bigproject.backend.domain.classroom.presentation.dto;

import java.util.List;

public record ClassroomListResponse(List<ClassroomResponse> classrooms) {
}
