// Bootstrap de la aplicación standalone: evita la dependencia de NgModules.
import { isDevMode } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { provideIonicAngular } from '@ionic/angular/standalone';
import { provideServiceWorker } from '@angular/service-worker';
import { AppComponent } from './app/app.component';

// Ionic se registra una vez para que los componentes funcionen igual en PWA y Capacitor.
bootstrapApplication(AppComponent, { providers: [provideIonicAngular(), provideServiceWorker('ngsw-worker.js', { enabled: !isDevMode(), registrationStrategy: 'registerWhenStable:30000' })] }).catch((error: unknown) => {
  // Re-lanzar mantiene visible un fallo de arranque sin introducir logging de depuración.
  throw error;
});
