package com.contractnotemanager.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

/** Executing broker, shown as "Counterpart". sfKey is null for brokers typed in locally. */
@Entity
@Getter
@Setter
public class Broker {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String sfKey;
    private String name;
    private String bic;
    private String lei;
}
