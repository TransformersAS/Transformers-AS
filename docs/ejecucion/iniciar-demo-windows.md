# Marketplace: demostración CU-08 y CU-11

**Prerrequisito:** Docker Desktop instalado y abierto, en modo Linux containers.
La primera ejecución necesita Internet y puede tardar varios minutos.

1. Descargar y descomprimir la entrega completa en una carpeta con permisos de escritura.
2. Abrir Docker Desktop y esperar a que esté listo.
3. Hacer doble clic en **Marketplace.exe**, junto a `compose.yaml`.
4. Esperar a que el navegador abra **http://localhost:4300**.

| Caso | Correo | Contraseña |
|---|---|---|
| CU-08: roles y sesiones | demo@marketplace.local | MarketplaceDemo123! |
| CU-11: pedido confirmado | comprador.demo@example.com | MarketplaceDemo123! |

Ambas cuentas están verificadas. CU-08 tiene COMPRADOR y VENDEDOR; CU-11 solo COMPRADOR.
En CU-11: **Mis pedidos → Ver detalle → Cancelar pedido → Otro**, escribir
**Cancelación de demostración CU-11.** y confirmar. El pago y reembolso usan el simulador
existente del proyecto; el pedido, inventario y persistencia usan el backend y MySQL reales.

- **Cerrar:** doble clic en `Cerrar Marketplace.cmd`. Cerrar la ventana del launcher solo cierra esa ventana.
- **Volver a abrir / resetear:** cerrar la ventana anterior y abrir `Marketplace.exe` nuevamente.
  El mismo pedido cancelado vuelve a Confirmado; los demás pedidos se conservan.
- No elimine `.env.demo`: conserva las contraseñas locales generadas para el volumen de esta demo.
- Si hay un error, la ventana permanece abierta. Revise Docker Desktop y que los puertos
  4300, 8080 y 3307 estén libres. El diagnóstico está en `launcher-logs/marketplace.log`.

El ejecutable no está firmado: Windows puede mostrar SmartScreen; verifique que recibió la entrega del autor.
