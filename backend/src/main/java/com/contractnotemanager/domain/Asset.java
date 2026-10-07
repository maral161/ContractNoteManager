package com.contractnotemanager.domain;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class Asset {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String sfKey;
    private String name;
    private String type;
    private String isin;
    private String domicile;
    private String quoteCurrencyCode;
    private String settlementCurrencyCode;
    private Integer settlementDuration;
    /** Number of decimals allowed for quantities (funds); null means whole units. */
    private Integer qtyDecimals;
    @JdbcTypeCode(SqlTypes.JSON)
    private String classifications;
    @JdbcTypeCode(SqlTypes.JSON)
    private String typeData;
    private boolean deleted;
}
