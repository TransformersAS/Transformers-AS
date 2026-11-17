# ASR-002: evidencia de protección de datos sensibles

Estas pruebas comprueban lo que el sistema ya hace. No cambian código de producción. El cifrado de campos personales en
la base de datos (AES-GCM, ADR-003) **no está implementado** y por eso no tiene prueba: sigue como trabajo pendiente.

## Cómo ejecutarlas

Desde `backend/demo`, con Docker abierto (la prueba de integración levanta MySQL con Testcontainers):

```powershell
.\mvnw.cmd test "-Dtest=Asr002*"
```

Son 7 pruebas en `src/test/java/com/transformersas/marketplace/security/` y tardan unos dos minutos.

## Checklist del ASR y su evidencia

| Punto del checklist | Estado | Evidencia |
|---|---|---|
| Conexiones cifradas con proveedores y correo | Comprobado en configuración | `Asr002TransportEncryptionTests` |
| PAN/CVV completos fuera del almacenamiento, los registros y la API | Comprobado | `Asr002SensitiveDataTests.elPagoDescartaPanYCvvYNoLosGuardaNiLosRegistra` |
| Ninguna cuenta consulta información privada de otra | Comprobado | `Asr002SensitiveDataTests.unCompradorNoAccedeALosDatosPrivadosDeOtro` y las pruebas de cada módulo (abajo) |
| Minimizar datos enviados a Gemini | Comprobado, con un límite | `Asr002SensitiveDataTests.geminiRecibeSoloProductosYNoLosDatosPersonalesDelComprador` |
| Cifrado de campos personales, claves y datos históricos | **No implementado** | Sin prueba |

### 1. Cifrado en tránsito (`Asr002TransportEncryptionTests`, 4 pruebas)

Las pruebas de integración de correo, logística y notificaciones apagan STARTTLS y HTTPS para usar servidores
simulados en la misma máquina. Por eso hacía falta comprobar aparte la configuración que se despliega:

- `application.properties` exige STARTTLS (`starttls.required=true`) y autenticación en el correo. Si el servidor no
  ofrece cifrado, el envío falla en vez de mandar el mensaje en claro.
- Logística y notificaciones exigen HTTPS (`require-https=true`).
- El cliente HTTP común (`ExternalRestClients`) rechaza una dirección `http://` cuando se exige HTTPS.
- Gemini se consulta por `https://`.

**Límite:** se comprueba la configuración, no un certificado real. Validar TLS contra los proveedores reales es tarea
del despliegue.

### 2. Datos de tarjeta (`elPagoDescartaPanYCvvYNoLosGuardaNiLosRegistra`)

La prueba hace una compra real con la tarjeta de prueba `4111 1111 1111 1111`, CVV, titular y vencimiento enviados en
la petición de pago. El DTO `PaymentRequest` no tiene esos campos, así que se descartan:

- El pedido se crea igual.
- La respuesta no contiene el número ni el titular.
- Se revisan **todas las columnas de texto de todas las tablas** de la base: ninguna contiene el número ni el titular.
- La salida de registros de la aplicación tampoco los contiene.

Controles para que la prueba no pase en vacío: la misma búsqueda sí encuentra el nombre del producto en `products` y
`order_items`, y la salida capturada sí contiene los registros de la compra.

### 3. Datos de otra cuenta (`unCompradorNoAccedeALosDatosPrivadosDeOtro`)

Dos compradores con el mismo rol. El segundo conoce los ids del pedido y de la dirección del primero:

- `GET /api/orders/{id}` responde 404, y sus listas de pedidos y direcciones salen vacías.
- No puede modificar la dirección (404) ni abrir una reclamación sobre esa compra (404).
- La dueña sigue viendo su pedido y su dirección no cambia.

Cada módulo tiene además sus propias pruebas de propiedad: `OrderOwnershipIntegrationTests`,
`FindOwnOrdersAuthorizationTests`, `OrderReceiptAuthorizationTests`, `BuyerOrderHistoryAuthorizationTests` y
`AddressesIntegrationTests`.

### 4. Datos a Gemini (`geminiRecibeSoloProductosYNoLosDatosPersonalesDelComprador`)

Un comprador con correo, nombre, dirección y teléfono pide recomendaciones. Solo el proveedor de IA está simulado con
WireMock. Lo que se envía a Gemini contiene el historial de productos y el catálogo, pero no el correo, el nombre, la
dirección ni el teléfono.

**Límite (por eso el ASR sigue en "Parcial"):** el término de una búsqueda libre se envía tal cual. Si un comprador
escribiera un dato personal en el buscador, llegaría a Gemini. Falta filtrar o resumir esas búsquedas.

## Lo que falta para cumplir el ASR completo

1. Cifrar con AES-GCM el correo, el teléfono y las direcciones en las entidades JPA, con la clave fuera del código
   (KMS o Vault) y una migración para los datos ya guardados.
2. Filtrar los datos personales de las búsquedas libres antes de enviarlas a Gemini.
3. Probar TLS contra los proveedores reales en el ambiente desplegado.
