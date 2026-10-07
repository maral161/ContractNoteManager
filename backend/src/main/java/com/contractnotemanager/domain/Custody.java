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
public class Custody {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String sfKey;
    private String name;
    private String tag;
    private String domicile;
    private boolean contractNotesEnabled;
    private boolean deleted;
    @JdbcTypeCode(SqlTypes.JSON)
    private String rawPayload;
}
