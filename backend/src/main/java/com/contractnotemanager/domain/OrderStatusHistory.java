package com.contractnotemanager.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class OrderStatusHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long orderId;
    @Enumerated(EnumType.STRING)
    private OrderStatus fromStatus;
    @Enumerated(EnumType.STRING)
    private OrderStatus toStatus;
    private Instant changedAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "\"trigger\"")
    private StatusTrigger trigger;
    private String note;

    public OrderStatusHistory(Long orderId, OrderStatus from, OrderStatus to, StatusTrigger trigger, String note) {
        this.orderId = orderId;
        this.fromStatus = from;
        this.toStatus = to;
        this.trigger = trigger;
        this.note = note;
        this.changedAt = Instant.now();
    }
}
