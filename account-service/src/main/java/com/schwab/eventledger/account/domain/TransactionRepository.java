package com.schwab.eventledger.account.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface TransactionRepository extends JpaRepository<TransactionEntity, String> {

    List<TransactionEntity> findByAccountIdOrderByEventTimestampAscEventIdAsc(String accountId);

    List<TransactionEntity> findTop20ByAccountIdOrderByEventTimestampDescEventIdDesc(String accountId);

    boolean existsByAccountId(String accountId);

    @Query("""
            select coalesce(sum(case when t.type = com.schwab.eventledger.account.domain.TransactionType.CREDIT
                                     then t.amount else -t.amount end), 0)
            from TransactionEntity t
            where t.accountId = :accountId
            """)
    BigDecimal computeBalance(@Param("accountId") String accountId);
}
