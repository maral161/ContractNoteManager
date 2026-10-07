package com.contractnotemanager.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import com.contractnotemanager.domain.Order;
import com.contractnotemanager.domain.OrderStatus;

public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {
    Optional<Order> findBySfKey(String sfKey);

    @Override
    @EntityGraph(attributePaths = {"asset", "custody", "owner", "broker"})
    Page<Order> findAll(Specification<Order> spec, Pageable pageable);

    /** Orders with the note's ISIN that no other contract note is linked to (any status, for the explanation). */
    @Query("""
            select o from Order o join fetch o.asset
            where o.asset.isin = :isin
              and not exists (select 1 from ContractNote n where n.order = o and n.id <> :noteId)
            """)
    List<Order> findMatchCandidates(String isin, Long noteId);

    @Query("select o from Order o join fetch o.asset where o.id in :ids")
    List<Order> findAllWithAssetByIdIn(Collection<Long> ids);
}
