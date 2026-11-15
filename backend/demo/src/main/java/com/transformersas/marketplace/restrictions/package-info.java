/**
 * CU-22 Administrar cuentas y restricciones de acceso. El administrador busca una cuenta, revisa sus antecedentes
 * confirmados (reclamaciones resueltas con responsabilidad de su tienda, decisiones de moderación que confirmaron un
 * incumplimiento y restricciones anteriores), puede pedirle una aclaración y decide si restringe un rol específico o
 * toda la cuenta, de forma temporal o permanente.
 *
 * <p>Este módulo guarda la restricción y su historia. Los efectos los aplican los demás módulos al comprobar la
 * cuenta: {@code users} (si puede operar como vendedora) y, por su intermedio, {@code stores} (la tienda queda
 * RESTRICTED en vez de reactivarse). Toda acción se audita y se notifica a la persona, dentro del marketplace y por
 * el servicio externo, sin exponer información administrativa de terceros (postcondición 8).
 */
package com.transformersas.marketplace.restrictions;
