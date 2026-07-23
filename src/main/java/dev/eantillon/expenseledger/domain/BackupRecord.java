package dev.eantillon.expenseledger.domain;

import java.time.Instant;

public record BackupRecord(
        String id,
        String path,
        String sha256,
        Long sizeBytes,
        String status,
        String error,
        Instant createdAt,
        Instant completedAt) {
}
