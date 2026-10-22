const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { runInNewContext } = require('node:vm');
const ts = require('typescript');

const exportsModule = {};
const source = readFileSync(resolve(__dirname, '../src/app/catalogo/catalogo-filtros.ts'), 'utf8');
runInNewContext(ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
}).outputText, { exports: exportsModule });
const { filtrarCatalogo, categoriaComparable } = exportsModule;
const productos = [
  { id: 1, nombre: 'Cafetera compacta', descripcion: 'Café en casa', categoria: 'Hogar', precio: 75000 },
  { id: 2, nombre: 'Lámpara LED', descripcion: 'Luz de lectura', categoria: 'Hogar', precio: 55000 },
  { id: 3, nombre: 'Camiseta', descripcion: 'Algodón', categoria: 'Ropa', precio: 35000 },
  { id: 4, nombre: 'Jean', categoria: 'Moda', precio: 95000 }
];
const ids = values => Array.from(values, p => p.id);

test('busca palabras en nombre y descripcion sin tildes ni mayusculas', () => {
  assert.deepEqual(ids(filtrarCatalogo(productos, 'LAMPARA lectura', '', null, null, 'relevancia')), [2]);
  assert.deepEqual(ids(filtrarCatalogo(productos, 'CAFE casa', '', null, null, 'relevancia')), [1]);
  assert.deepEqual(ids(filtrarCatalogo(productos, 'sin coincidencias', '', null, null, 'relevancia')), []);
});

test('combina categoria y limites de precio inclusivos', () => {
  assert.deepEqual(ids(filtrarCatalogo(productos, '', 'hogar', 55000, 75000, 'precio-desc')), [1, 2]);
  assert.deepEqual(ids(filtrarCatalogo(productos, '', 'hogar', 75000, 75000, 'precio-asc')), [1]);
  assert.deepEqual(ids(filtrarCatalogo(productos, '', 'hogar', 80000, 70000, 'relevancia')), []);
});

test('considera ropa y moda comparables y filtra ambas', () => {
  assert.equal(categoriaComparable(' ROPA '), categoriaComparable('Moda'));
  assert.deepEqual(ids(filtrarCatalogo(productos, '', 'moda', null, null, 'relevancia')), [3, 4]);
});

test('ordena sin modificar la coleccion original y limpia filtros', () => {
  assert.deepEqual(ids(filtrarCatalogo(productos, '', '', null, null, 'precio-asc')), [3, 2, 1, 4]);
  assert.deepEqual(ids(filtrarCatalogo(productos, '', '', null, null, 'recientes')), [4, 3, 2, 1]);
  assert.deepEqual(ids(filtrarCatalogo(productos, '', '', null, null, 'nombre')), [1, 3, 4, 2]);
  assert.deepEqual(ids(filtrarCatalogo(productos, '', '', null, null, 'relevancia')), [1, 2, 3, 4]);
  assert.deepEqual(productos.map(p => p.id), [1, 2, 3, 4]);
});
