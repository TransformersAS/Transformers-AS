package com.transformersas.marketplace.security;

import com.transformersas.marketplace.recommendation.infrastructure.GeminiRecommendationClient;
import com.transformersas.marketplace.shared.http.ExternalRestClients;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Evidencia de ASR-002, cifrado en tránsito. Las pruebas de integración de correo, logística y notificaciones apagan
 * STARTTLS y HTTPS para hablar con servidores simulados en la propia máquina; estas comprueban que la configuración
 * que se despliega los exige y que el cliente HTTP común rechaza un proveedor sin HTTPS.
 */
class Asr002TransportEncryptionTests {

    /** application.properties de producción, el que empaqueta la imagen del backend. */
    private static Properties configuracion() throws Exception {
        Properties propiedades = new Properties();
        try (InputStream archivo = Asr002TransportEncryptionTests.class.getResourceAsStream("/application.properties")) {
            assertThat(archivo).as("application.properties en el classpath").isNotNull();
            propiedades.load(archivo);
        }
        return propiedades;
    }

    @Test
    void elCorreoExigeStarttlsYAutenticacion() throws Exception {
        Properties p = configuracion();
        // required=true: si el servidor no ofrece cifrado, el envío falla en lugar de mandar el correo en claro.
        assertThat(p.getProperty("spring.mail.properties.mail.smtp.starttls.enable")).isEqualTo("true");
        assertThat(p.getProperty("spring.mail.properties.mail.smtp.starttls.required")).isEqualTo("true");
        assertThat(p.getProperty("spring.mail.properties.mail.smtp.auth")).isEqualTo("true");
    }

    @Test
    void losProveedoresExternosExigenHttps() throws Exception {
        Properties p = configuracion();
        assertThat(p.getProperty("logistics.http.require-https")).isEqualTo("true");
        assertThat(p.getProperty("notifications.http.require-https")).isEqualTo("true");
    }

    @Test
    void elClienteHttpComunRechazaUnProveedorSinHttps() {
        Duration timeout = Duration.ofSeconds(2);
        assertThatThrownBy(() -> ExternalRestClients.build(RestClient.builder(), "http://logistica.example.com", timeout,
                true, "logistics.http"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("debe usar HTTPS");
        assertThat(ExternalRestClients.build(RestClient.builder(), "https://logistica.example.com", timeout, true,
                "logistics.http")).isNotNull();
    }

    @Test
    void geminiSeConsultaPorHttps() {
        GeminiRecommendationClient cliente = new GeminiRecommendationClient("", "modelo");
        assertThat((String) ReflectionTestUtils.getField(cliente, "baseUrl")).startsWith("https://");
    }
}
