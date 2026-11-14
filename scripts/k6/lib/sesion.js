import http from 'k6/http';

// Utilidades comunes de las pruebas de carga con sesión: cada usuario virtual entra UNA vez con su
// propia cuenta y reutiliza la cookie. El login usa BCrypt a propósito, así que queda fuera de las métricas.

export const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';

/**
 * Cuenta del usuario virtual. Con ACCOUNT_PATTERN (por ejemplo buyer{n}@marketplace.demo, el pool de
 * scripts/performance-demo-seed.sh) cada usuario virtual usa una cuenta distinta, que es lo que exigen
 * carrito, favoritos y direcciones: son recursos por cuenta. Sin patrón se usa ACCOUNT_EMAIL para todos.
 */
export function cuenta(vu) {
  const password = __ENV.ACCOUNT_PASSWORD;
  const pattern = __ENV.ACCOUNT_PATTERN;
  if (!password || (!pattern && !__ENV.ACCOUNT_EMAIL)) {
    throw new Error('Define ACCOUNT_PASSWORD y ACCOUNT_PATTERN (o ACCOUNT_EMAIL) de cuentas de prueba ya verificadas.');
  }
  if (!pattern) {
    return { email: __ENV.ACCOUNT_EMAIL, password };
  }
  const total = Number(__ENV.ACCOUNT_POOL || 100);
  const numero = String(((vu - 1) % total) + 1).padStart(3, '0');
  return { email: pattern.replace('{n}', numero), password };
}

/** Token CSRF de la sesión; el backend lo exige en todo lo que modifica datos. */
export function csrf(jar) {
  const response = http.get(`${BASE_URL}/api/auth/csrf`, { jar, tags: { name: 'csrf' } });
  if (response.status !== 200) {
    return null;
  }
  return { [response.json('headerName')]: response.json('token') };
}

/** Inicia sesión y devuelve las cabeceras (CSRF + JSON) para las escrituras, o null si falla. */
export function entrar(jar, credenciales) {
  const antes = csrf(jar);
  if (!antes) {
    return null;
  }
  const response = http.post(`${BASE_URL}/api/auth/login`,
    { email: credenciales.email, password: credenciales.password },
    { headers: antes, jar, tags: { name: 'login' } });
  if (response.status !== 204) {
    console.error(`POST /api/auth/login (${credenciales.email}): status=${response.status}`);
    return null;
  }
  // El token cambia al autenticarse: se pide el de la sesión ya iniciada.
  const despues = csrf(jar);
  return despues ? Object.assign({ 'Content-Type': 'application/json' }, despues) : null;
}

/** Productos comprables con stock holgado, para que la carga no falle por inventario agotado. */
export function productosComprables(jar, stockMinimo) {
  const response = http.get(`${BASE_URL}/api/products`, { jar, tags: { name: 'setup' } });
  let productos;
  try { productos = response.json(); } catch (_) { productos = null; }
  if (response.status !== 200 || !Array.isArray(productos)) {
    throw new Error(`Preflight: GET /api/products respondió ${response.status}.`);
  }
  const ids = productos.filter((p) => p.active && p.stock >= stockMinimo).map((p) => p.id);
  if (ids.length === 0) {
    throw new Error('Preflight: no hay productos activos con stock suficiente. No hay prueba válida.');
  }
  return ids;
}
