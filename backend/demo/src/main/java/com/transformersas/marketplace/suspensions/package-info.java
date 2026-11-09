/**
 * CU-27 Evaluar y aplicar suspensiones de tiendas. El administrador revisa los antecedentes confirmados de una tienda
 * (reclamaciones resueltas con responsabilidad del vendedor, decisiones de moderación que confirmaron un
 * incumplimiento y suspensiones anteriores), puede pedir una aclaración al vendedor y decide si suspende
 * temporalmente su operación comercial.
 *
 * <p>Este módulo guarda la suspensión y su historia; el estado de la tienda pertenece a {@code stores} y se cambia con
 * sus casos de uso. Los efectos (no publicar ni recibir compras) los aplican los módulos de productos y checkout al
 * comprobar que la tienda está ACTIVE. Toda acción se audita y se notifica al vendedor, dentro del marketplace y por
 * el servicio externo.
 */
package com.transformersas.marketplace.suspensions;
