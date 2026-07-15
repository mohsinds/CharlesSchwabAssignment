package com.schwab.eventledger.gateway.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventRepository extends JpaRepository<EventEntity, String> {

    List<EventEntity> findByAccountIdOrderByEventTimestampAscEventIdAsc(String accountId);

    List<EventEntity> findByStatusOrderByCreatedAtAsc(EventStatus status);

    long countByStatus(EventStatus status);
}
