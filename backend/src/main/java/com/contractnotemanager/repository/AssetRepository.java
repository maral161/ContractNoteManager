package com.contractnotemanager.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.contractnotemanager.domain.Asset;

public interface AssetRepository extends JpaRepository<Asset, Long> {
    Optional<Asset> findBySfKey(String sfKey);
}
