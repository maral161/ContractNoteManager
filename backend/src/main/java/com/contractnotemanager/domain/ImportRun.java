package com.contractnotemanager.domain;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class ImportRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Instant startedAt;
    private Instant finishedAt;
    @Enumerated(EnumType.STRING)
    private ImportRunStatus status;
    private LocalDate fromDate;
    private LocalDate toDate;
    private Integer expectedCount;
    private int createdCount;
    private int updatedCount;
    private int skippedCount;
    private int conflictCount;
    private int failedCount;
    private int detailsMissingCount;
    private String errorMessage;
}
