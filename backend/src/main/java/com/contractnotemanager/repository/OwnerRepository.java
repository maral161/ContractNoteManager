package com.contractnotemanager.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.contractnotemanager.domain.Owner;

public interface OwnerRepository extends JpaRepository<Owner, Long> {
    Optional<Owner> findBySfKey(String sfKey);
}
