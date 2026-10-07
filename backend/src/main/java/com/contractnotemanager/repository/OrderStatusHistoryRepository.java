package com.contractnotemanager.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.contractnotemanager.domain.OrderStatusHistory;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, Long> {
    List<OrderStatusHistory> findByOrderIdOrderByChangedAtAscIdAsc(Long orderId);

    void deleteByOrderId(Long orderId);
}
