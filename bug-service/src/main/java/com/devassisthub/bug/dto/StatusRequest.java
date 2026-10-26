package com.devassisthub.bug.dto;

import com.devassisthub.bug.entity.BugStatus;
import jakarta.validation.constraints.NotNull;

public record StatusRequest(@NotNull BugStatus status) {
}
