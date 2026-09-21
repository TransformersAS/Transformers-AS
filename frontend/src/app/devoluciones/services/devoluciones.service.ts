import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, finalize, map, shareReplay, tap } from 'rxjs';

import { API_BASE } from '../../core/config/api.config';
import {
    DetalleDevolucion,
    DevolucionSolicitada,
    EstadoDevolucion,
    LineaElegible,
    MetodoRetorno,
    PedidoElegible,
    ResumenDevolucion,
    SolicitudDevolucion
} from '../models/devolucion.model';

/** Cuánto tiempo se reutiliza en memoria la lista de líneas elegibles antes de volver a pedirla. */
const VALIDEZ_ELEGIBLES_MS = 60_000;

/**
 * Devoluciones de compra (CU-19). La sesión y el CSRF los aporta sessionInterceptor. También guarda qué panel debe estar
 * abierto y qué devolución mostrar, para que el botón «Devolver» de un pedido pueda llevar hasta una existente.
 */
@Injectable({ providedIn: 'root' })
export class DevolucionesService {
    private readonly http = inject(HttpClient);
    private readonly urlComprador = `${API_BASE}/return-requests`;
    private readonly urlVendedor = `${API_BASE}/seller/return-requests`;

    private readonly _panelCompradorAbierto = signal(false);
    private readonly _devolucionSeleccionada = signal<number | null>(null);
    private readonly _panelVendedorAbierto = signal(false);
    readonly panelCompradorAbierto = this._panelCompradorAbierto.asReadonly();
    readonly devolucionSeleccionada = this._devolucionSeleccionada.asReadonly();
    readonly panelVendedorAbierto = this._panelVendedorAbierto.asReadonly();

    abrirPanelComprador(devolucionId: number | null = null): void {
        this._devolucionSeleccionada.set(devolucionId);
        this._panelCompradorAbierto.set(true);
    }

    cerrarPanelComprador(): void {
        this._panelCompradorAbierto.set(false);
        this._devolucionSeleccionada.set(null);
    }

    abrirPanelVendedor(): void {
        this._panelVendedorAbierto.set(true);
    }

    cerrarPanelVendedor(): void {
        this._panelVendedorAbierto.set(false);
    }

    // ---------- Comprador ----------

    /**
     * Una sola petición para todos los pedidos entregados: la respuesta se guarda en memoria y la comparten todas las
     * líneas de todos los pedidos que se abran, sin una petición por línea. Se vuelve a pedir si pasó el tiempo de
     * validez o si se invalidó (al solicitar una devolución o elegir un método).
     */
    private elegibles$: Observable<PedidoElegible[]> | null = null;
    private elegiblesPedidoEn = 0;

    /** Líneas de un pedido entregado, con si se pueden devolver. Solo se consulta al abrir el detalle del pedido. */
    lineasDelPedido(pedidoId: number): Observable<LineaElegible[]> {
        return this.pedidosElegibles().pipe(map((pedidos) => pedidos.find((p) => p.orderId === pedidoId)?.lines ?? []));
    }

    pedidosElegibles(): Observable<PedidoElegible[]> {
        if (this.elegibles$ === null || Date.now() - this.elegiblesPedidoEn > VALIDEZ_ELEGIBLES_MS) {
            this.elegiblesPedidoEn = Date.now();
            this.elegibles$ = this.http.get<PedidoElegible[]>(`${this.urlComprador}/eligible-orders`)
                .pipe(shareReplay({ bufferSize: 1, refCount: false }));
        }
        return this.elegibles$;
    }

    /** Olvida lo que se sabe de elegibilidad (tras solicitar o al reabrir la pantalla). */
    invalidarElegibles(): void {
        this.elegibles$ = null;
    }

    /** Sin imágenes va como JSON; con imágenes, como formulario multipart (parte "evidences"). */
    solicitar(solicitud: SolicitudDevolucion, imagenes: File[]): Observable<DevolucionSolicitada> {
        const peticion = imagenes.length === 0
            ? this.http.post<DevolucionSolicitada>(this.urlComprador, solicitud)
            : this.http.post<DevolucionSolicitada>(this.urlComprador, this.formulario(solicitud, imagenes));
        return peticion.pipe(tap(() => this.invalidarElegibles()));
    }

    private formulario(solicitud: SolicitudDevolucion, imagenes: File[]): FormData {
        const formulario = new FormData();
        formulario.append('orderId', String(solicitud.orderId));
        formulario.append('orderItemId', String(solicitud.orderItemId));
        formulario.append('reason', solicitud.reason);
        formulario.append('description', solicitud.description);
        imagenes.forEach((imagen) => formulario.append('evidences', imagen, imagen.name));
        return formulario;
    }

    mias(): Observable<ResumenDevolucion[]> {
        return this.http.get<ResumenDevolucion[]>(this.urlComprador);
    }

    detalle(id: number): Observable<DetalleDevolucion> {
        return this.http.get<DetalleDevolucion>(`${this.urlComprador}/${id}`);
    }

    responder(id: number, texto: string): Observable<DetalleDevolucion> {
        return this.http.post<DetalleDevolucion>(`${this.urlComprador}/${id}/information-response`, { text: texto });
    }

    metodosDeRetorno(id: number): Observable<MetodoRetorno[]> {
        return this.http.get<MetodoRetorno[]>(`${this.urlComprador}/${id}/return-methods`);
    }

    elegirMetodo(id: number, metodo: string): Observable<DetalleDevolucion> {
        return this.http.post<DetalleDevolucion>(`${this.urlComprador}/${id}/return-method`, { method: metodo })
            .pipe(finalize(() => this.invalidarElegibles()));
    }

    /** URL de una imagen de la solicitud: el navegador la pide con la cookie de sesión y la cachea con ETag. */
    urlEvidenciaComprador(id: number, ordinal: number): string {
        return `${this.urlComprador}/${id}/evidences/${ordinal}`;
    }

    // ---------- Vendedor (solo la tienda de la sesión) ----------

    recibidas(estado: EstadoDevolucion | null): Observable<ResumenDevolucion[]> {
        const params = estado ? new HttpParams().set('status', estado) : new HttpParams();
        return this.http.get<ResumenDevolucion[]>(this.urlVendedor, { params });
    }

    detalleVendedor(id: number): Observable<DetalleDevolucion> {
        return this.http.get<DetalleDevolucion>(`${this.urlVendedor}/${id}`);
    }

    revisar(id: number): Observable<DetalleDevolucion> {
        return this.http.post<DetalleDevolucion>(`${this.urlVendedor}/${id}/review`, {});
    }

    pedirInformacion(id: number, mensaje: string): Observable<DetalleDevolucion> {
        return this.http.post<DetalleDevolucion>(`${this.urlVendedor}/${id}/information-requests`, { message: mensaje });
    }

    aprobar(id: number, nota: string | null): Observable<DetalleDevolucion> {
        return this.http.post<DetalleDevolucion>(`${this.urlVendedor}/${id}/approve`, { note: nota });
    }

    rechazar(id: number, nota: string): Observable<DetalleDevolucion> {
        return this.http.post<DetalleDevolucion>(`${this.urlVendedor}/${id}/reject`, { note: nota });
    }

    reportarProblema(id: number, descripcion: string): Observable<DetalleDevolucion> {
        return this.http.post<DetalleDevolucion>(`${this.urlVendedor}/${id}/report-problem`, { description: descripcion });
    }

    urlEvidenciaVendedor(id: number, ordinal: number): string {
        return `${this.urlVendedor}/${id}/evidences/${ordinal}`;
    }
}
