package com.transformersas.marketplace.stores.domain.model;

/**
 * Estado operativo de la tienda. Lo consultan CU-18 (limita las modificaciones) y lo escribirán CU-22 y CU-27
 * cuando suspendan o restrinjan una tienda.
 */
public enum StoreStatus { ACTIVE, RESTRICTED, SUSPENDED }
