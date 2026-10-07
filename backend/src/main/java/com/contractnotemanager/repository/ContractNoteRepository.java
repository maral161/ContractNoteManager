package com.contractnotemanager.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.contractnotemanager.domain.ContractNote;
import com.contractnotemanager.domain.ContractNoteStatus;

public interface ContractNoteRepository extends JpaRepository<ContractNote, Long> {
    Optional<ContractNote> findByFileSha256(String sha256);

    Optional<ContractNote> findByOrderId(Long orderId);

    List<ContractNote> findByStatusInOrderByCreatedAtDesc(Collection<ContractNoteStatus> statuses);

    long countByStatusIn(Collection<ContractNoteStatus> statuses);
}
