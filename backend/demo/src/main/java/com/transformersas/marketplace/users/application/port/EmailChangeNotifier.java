package com.transformersas.marketplace.users.application.port;

/** Canal exclusivo para confirmar un correo nuevo, independiente del alta de CU08. */
public interface EmailChangeNotifier { void send(String newEmail,String token); }
