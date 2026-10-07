package com.contractnotemanager.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.contractnotemanager.domain.Broker;

public interface BrokerRepository extends JpaRepository<Broker, Long> {
    Optional<Broker> findBySfKey(String sfKey);

    Optional<Broker> findFirstByNameIgnoreCase(String name);
}
