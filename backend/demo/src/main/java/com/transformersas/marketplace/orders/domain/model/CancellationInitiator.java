package com.transformersas.marketplace.orders.domain.model;

/** Quién inicia la cancelación. CU-23 expone la del vendedor; CU-11 usará BUYER con el mismo caso de uso. */
public enum CancellationInitiator { SELLER, BUYER }
