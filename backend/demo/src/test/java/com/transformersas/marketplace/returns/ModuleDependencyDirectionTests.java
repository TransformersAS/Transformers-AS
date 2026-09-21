package com.transformersas.marketplace.returns;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El repositorio no tiene pruebas de arquitectura (ni ArchUnit ni Modulith) que impidan ciclos entre módulos. Esta es la
 * mínima que hace falta para CU-19: devoluciones depende de reclamaciones a través de un puerto, así que reclamaciones no
 * puede importar nada de devoluciones. Si CU-13 necesita algo de devoluciones, tiene que hacerlo con un evento o un puerto
 * propio, no importando sus clases.
 */
class ModuleDependencyDirectionTests {
    private static final Path MAIN = Path.of("src/main/java/com/transformersas/marketplace");

    private static boolean importsFrom(Path module, String forbiddenPackage) throws IOException {
        try (Stream<Path> files = Files.walk(module)) {
            return files.filter(path -> path.toString().endsWith(".java")).anyMatch(path -> {
                try {
                    return Files.readString(path).contains("com.transformersas.marketplace." + forbiddenPackage);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    @Test
    void claimsDoesNotDependOnReturns() throws IOException {
        assertThat(importsFrom(MAIN.resolve("claims"), "returns")).isFalse();
    }

    @Test
    void theReturnsDomainDoesNotDependOnOtherModulesOrOnSpring() throws IOException {
        Path domain = MAIN.resolve("returns/domain");
        for (String forbidden : new String[]{"claims", "orders", "stores", "payments", "logistics", "notifications"}) {
            assertThat(importsFrom(domain, forbidden)).as("dominio de returns importa " + forbidden).isFalse();
        }
        try (Stream<Path> files = Files.walk(domain)) {
            assertThat(files.filter(path -> path.toString().endsWith(".java"))
                    .noneMatch(path -> contains(path, "org.springframework"))).isTrue();
        }
    }

    private static boolean contains(Path path, String text) {
        try {
            return Files.readString(path).contains(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
