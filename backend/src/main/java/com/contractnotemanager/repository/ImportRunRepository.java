package com.contractnotemanager.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.contractnotemanager.domain.ImportRun;

public interface ImportRunRepository extends JpaRepository<ImportRun, Long> {
    List<ImportRun> findTop50ByOrderByStartedAtDesc();
}
