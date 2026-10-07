package com.contractnotemanager.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "orders")
@Getter
@Setter
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sfKey;
    private int sfVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "custody_id")
    private Custody custody;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id")
    private Asset asset;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private Owner owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "broker_id")
    private Broker broker;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    private String sfStatus;
    private String orderType;
    private String side;
    private BigDecimal price;
    private BigDecimal value;
    private String currencyCode;
    private BigDecimal settlementAmount;
    private BigDecimal commission = BigDecimal.ZERO;
    private BigDecimal accruedInterest = BigDecimal.ZERO;
    private BigDecimal upFrontFee = BigDecimal.ZERO;
    private BigDecimal issuerFee = BigDecimal.ZERO;
    private LocalDate bookedDate;
    private LocalDate tradedDate;
    private LocalDate settlementDate;
    private LocalDate validTo;
    private String source;
    private String comment;
    private boolean partialFillAllowed;
    private boolean isMerged;
    private boolean handledManually;
    private boolean handledManuallyEligible;
    @JdbcTypeCode(SqlTypes.JSON)
    private String exchange;
    private boolean sfDeleted;

    private boolean locallyModified;
    private boolean syncConflict;
    private boolean detailsMissing;
    private Instant lastSyncedAt;
    private Instant detailsImportedAt;
    /** Last order payload imported from Sharpfin; used by "revert". */
    @JdbcTypeCode(SqlTypes.JSON)
    private String rawPayload;

    @Version
    private int version;

    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<OrderAllocation> allocations = new ArrayList<>();

    /** 1 when a contract note is matched to this order; used for the "Contract Note Match" column. */
    @Formula("(select count(*) from contract_note cn where cn.order_id = id)")
    private int noteMatched;

    public boolean isSell() {
        return "sell".equalsIgnoreCase(side);
    }

    public boolean isAmountOrder() {
        return "amount".equalsIgnoreCase(orderType);
    }

    public void addAllocation(OrderAllocation allocation) {
        allocation.setOrder(this);
        allocations.add(allocation);
    }
}
