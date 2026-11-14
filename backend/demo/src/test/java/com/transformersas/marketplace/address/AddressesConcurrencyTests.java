package com.transformersas.marketplace.address;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import com.transformersas.marketplace.address.dto.AddressRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.TestPropertySource;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Local MockMvc/MySQL measurement; excludes account provisioning and external network latency. */
@EnabledIfSystemProperty(named="cu06.performance",matches="true")
@TestPropertySource(properties={"logging.level.root=WARN","logging.level.org.springframework.jdbc=OFF"})
class AddressesConcurrencyTests extends AbstractIntegrationTest {
    @Test void oneHundredBuyersManageTheirOwnAddressesWithinTwoSecondP95() throws Exception {
        var sessions=new ArrayList<Session>();var ids=new ArrayList<Long>();var otherIds=new ArrayList<Long>();
        for(int i=0;i<100;i++){
            sessions.add(sessionWithRole("address-load-"+i+"@example.test","COMPRADOR"));
            var response=perform(sessions.getLast(),post("/api/addresses").contentType("application/json").content(json.writeValueAsString(new AddressRequest("Comprador "+i,"Calle 1","Bogotá","Cundinamarca","","3001234567")))).andReturn();
            assertThat(response.getResponse().getStatus()).isEqualTo(201);
            ids.add(json.readTree(response.getResponse().getContentAsString()).get("id").asLong());
            var second=perform(sessions.getLast(),post("/api/addresses").contentType("application/json").content(json.writeValueAsString(new AddressRequest("Comprador "+i,"Oficina","Bogotá","Cundinamarca","","3001234567")))).andReturn().getResponse();
            assertThat(second.getStatus()).isEqualTo(201);otherIds.add(json.readTree(second.getContentAsString()).get("id").asLong());
        }
        var times=new ConcurrentLinkedQueue<Double>();var ready=new CountDownLatch(100);var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var jobs=new ArrayList<Future<?>>();
            for(int i=0;i<100;i++){final int index=i;jobs.add(executor.submit(()->{
                ready.countDown();start.await();
                String body=json.writeValueAsString(new AddressRequest("Comprador "+index,"Calle nueva","Bogotá","Cundinamarca","","3001234567"));
                for(int round=0;round<3;round++){int operation=0;long target=round%2==0?otherIds.get(index):ids.get(index);
                  for(var request:List.of(get("/api/addresses"),put("/api/addresses/"+ids.get(index)).contentType("application/json").content(body),put("/api/addresses/"+target+"/default"))){
                    long began=System.nanoTime();var result=perform(sessions.get(index),request).andReturn().getResponse();times.add((System.nanoTime()-began)/1_000_000.0);assertThat(result.getStatus()).isEqualTo(200);
                    if(operation++==0){var rows=json.readTree(result.getContentAsString());assertThat(rows.size()).isEqualTo(2);for(int row=0;row<2;row++)assertThat(rows.get(row).get("id").asLong()).isIn(ids.get(index),otherIds.get(index));}
                  }
                }
                return null;
            }));}
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();for(var job:jobs)job.get(120,TimeUnit.SECONDS);
        }
        var sorted=times.stream().sorted().toList();double p95=sorted.get((int)Math.ceil(sorted.size()*.95)-1);
        System.out.printf(Locale.ROOT,"CU06_PERFORMANCE sessions=100 requests=%d p95_ms=%.2f%n",sorted.size(),p95);
        assertThat(p95).isLessThanOrEqualTo(2000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM addresses WHERE is_default=TRUE AND deleted=FALSE",Long.class)).isEqualTo(100);
    }
}
