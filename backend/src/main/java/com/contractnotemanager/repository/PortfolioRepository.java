package com.contractnotemanager.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.contractnotemanager.domain.Portfolio;

public interface PortfolioRepository extends JpaRepository<Portfolio, Long> {
    Optional<Portfolio> findBySfKey(String sfKey);
}
