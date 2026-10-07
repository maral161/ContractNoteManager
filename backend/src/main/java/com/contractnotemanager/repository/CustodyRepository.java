package com.contractnotemanager.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.contractnotemanager.domain.Custody;

public interface CustodyRepository extends JpaRepository<Custody, Long> {
    Optional<Custody> findBySfKey(String sfKey);
}
