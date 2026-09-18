# Marketplace Integral — Frontend

Aplicación Ionic + Angular standalone, preparada para navegador como PWA y para empaquetarse con Capacitor. Los datos de la portada son mocks encapsulados en servicios tipados; no existe dependencia ni cambio alguno sobre Spring Boot.

## Ejecutar

```bash
npm install
npm start
```

El servidor de desarrollo usa `http://localhost:4300` para no colisionar con procesos habituales en el puerto 4200.

Para una compilación productiva (incluye el service worker):

```bash
npm run build
```

Al instalar las plataformas de Capacitor que corresponda al equipo, `capacitor.config.ts` usa `dist/browser` como el artefacto web a sincronizar.
