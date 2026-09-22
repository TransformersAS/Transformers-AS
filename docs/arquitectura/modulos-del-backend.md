# Organización de módulos de negocio

Cada módulo conserva sus reglas y dependencias dentro de su propio paquete. La
estructura se creó en `auth`, `users`, `catalog`, `inventory`, `orders`,
`payments`, `logistics`, `reviews`, `returns` y `notifications`:

```text
<modulo>/
  domain/
    model/                 # Agregados, value objects, reglas e invariantes.
    repository/            # Puertos que el dominio necesita.
  application/
    dto/                   # Datos de entrada y salida de los casos de uso.
    usecase/               # Coordinación de una operación de negocio.
  infrastructure/
    persistence/
      entity/              # Entidades JPA privadas, nunca respuestas HTTP.
      repository/          # Adaptadores que implementan los puertos.
      mapper/              # Conversión entidad JPA <-> modelo de dominio.
    web/
      controller/          # Adaptadores HTTP delgados.
      request/             # Contratos JSON de entrada y validaciones HTTP.
      response/            # Contratos JSON de salida.
```

## Reglas de dependencia

- Un controller valida el request, llama a un único caso de uso y traduce su
  resultado a un response. No contiene reglas de negocio ni usa repositorios JPA.
- Un caso de uso depende de modelos y puertos del dominio; no depende de
  `Controller`, `Request`, `Response` ni clases de Spring MVC.
- Una entidad JPA pertenece a `infrastructure.persistence.entity`. No se expone
  por la API ni se reutiliza como DTO.
- Los DTO de aplicación no son los request/response HTTP. La capa web transforma
  entre ambos cuando sus contratos tengan responsabilidades distintas.
- Un módulo no accede a entidades ni repositorios internos de otro módulo. La
  colaboración se acuerda mediante casos de uso o contratos explícitos.
- `shared` solo aloja asuntos técnicos o contratos realmente transversales; no
  es un destino para lógica de negocio.

## Al crear un caso de uso

1. Acordar el contrato HTTP y las reglas de negocio.
2. Modelar el agregado y sus puertos en el módulo propietario.
3. Implementar el caso de uso y sus DTO de aplicación.
4. Crear el adaptador JPA, mappers y una migración Flyway nueva si hay persistencia.
5. Crear controller, request y response sin filtrar entidades JPA.
6. Añadir pruebas del caso de uso y del adaptador web/persistencia que corresponda.
