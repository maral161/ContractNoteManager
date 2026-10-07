package com.contractnotemanager.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Basic;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import lombok.Getter;
import lombok.Setter;

/** An uploaded contract-note PDF, the fields Claude read from it, and its match with an order. */
@Entity
@Getter
@Setter
public class ContractNote {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fileName;
    private String fileSha256;
    @Basic(fetch = FetchType.LAZY)
    @JdbcTypeCode(SqlTypes.VARBINARY)
    private byte[] fileContent;

    private String instrumentName;
    private String isin;
    private String currencyCode;
    private BigDecimal quantity;
    private BigDecimal price;
    private BigDecimal settlementAmount;
    private String broker;
    private BigDecimal commission;
    private String side;
    private LocalDate tradeDate;
    /** Warnings from the extraction and the plausibility check, one per line. */
    private String warnings;
    @JdbcTypeCode(SqlTypes.JSON)
    private String extractionJson;
    private String extractionModel;

    @Enumerated(EnumType.STRING)
    private ContractNoteStatus status;
    private String unmatchedReason;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;
    private Instant matchedAt;
    /** How many of the six checks passed against the linked or closest order. */
    private Integer matchScore;
    /** The six checks with the values compared (JSON list). */
    @JdbcTypeCode(SqlTypes.JSON)
    private String matchChecks;

    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;
}
