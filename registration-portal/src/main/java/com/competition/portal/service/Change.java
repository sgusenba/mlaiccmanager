package com.competition.portal.service;

/** A change to a stored document, run while its file is locked; returns the caller's result. */
@FunctionalInterface
public interface Change<T, R> {
    R apply(T data) throws Exception;
}
