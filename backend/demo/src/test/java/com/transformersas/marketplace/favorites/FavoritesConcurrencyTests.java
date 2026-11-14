package com.transformersas.marketplace.favorites;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Local servlet/MySQL benchmark: does not include a real browser, network or reverse proxy. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="cu04.performance", matches="true")
@org.springframework.test.context.TestPropertySource(properties="logging.level.root=WARN")
class FavoritesConcurrencyTests extends AbstractIntegrationTest {
    @Test void oneHundredBuyerSessionsReadAndWriteTheirOwnFavorites() throws Exception {
        long product=seedProduct(1,"Favorito concurrente",20,"100");
        var sessions=new ArrayList<Session>();
        for(int i=0;i<100;i++) sessions.add(sessionWithRole("favorite-load-"+i+"@example.test","COMPRADOR"));
        var latencies=new ConcurrentLinkedQueue<Double>();
        var ready=new CountDownLatch(100);var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs=new ArrayList<Future<?>>();
            for(var session:sessions) jobs.add(executor.submit(()->{
                ready.countDown();start.await();
                for(int i=0;i<3;i++) {
                    var requests=List.of(put("/api/favorites/"+product),get("/api/favorites"),delete("/api/favorites/"+product));
                    for(var request:requests) {
                        long began=System.nanoTime();
                        int status=mvc.perform(session.apply(request)).andReturn().getResponse().getStatus();
                        latencies.add((System.nanoTime()-began)/1_000_000.0);
                        assertThat(status).isEqualTo(200);
                    }
                }
                return null;
            }));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            for(var job:jobs) job.get(120,TimeUnit.SECONDS);
        }
        var sorted=latencies.stream().sorted().toList();double p95=sorted.get((int)Math.ceil(sorted.size()*.95)-1);
        System.out.printf(java.util.Locale.ROOT,"CU04_CONCURRENCY sessions=100 requests=%d p95_ms=%.2f%n",sorted.size(),p95);
        assertThat(p95).isLessThanOrEqualTo(2000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM buyer_favorites",Integer.class)).isZero();
    }
}
