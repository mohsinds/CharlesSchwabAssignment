package com.schwab.eventledger.gateway.domain;

public enum EventStatus {
    PENDING,
    APPLIED,
    /** Terminal: Account rejected with a permanent 4xx (e.g. insufficient funds). */
    REJECTED
}
