package com.contractnotemanager.domain;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

/** The part of an order that belongs to one portfolio, with the holdings figures from Sharpfin. */
@Entity
@Getter
@Setter
public class OrderAllocation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "portfolio_id")
    private Portfolio portfolio;

    /** Current quantity (or amount for amount orders) – "New Quantity" in the edit modal. */
    private BigDecimal value;
    /** Quantity as imported – "Order Quantity" in the edit modal. */
    private BigDecimal originalValue;
    private String status;
    private String reason;
    private String externalId;
    private BigDecimal commission;
    private BigDecimal portfolioQuantity;
    private BigDecimal portfolioWeight;
    private BigDecimal targetQuantity;
    private BigDecimal targetWeight;
    private BigDecimal orderWeight;
}
