package com.transformersas.marketplace.favorites;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.stream.IntStream;
import static org.mockito.Mockito.*;

class ProductAlertSchedulerTests {
    @Test void failingAlertDoesNotBlockTheRestAndShortBatchResetsCursor() {
        var jdbc=mock(JdbcTemplate.class);var service=mock(ProductAlertService.class);
        when(jdbc.queryForList(anyString(),eq(String.class),eq(""))).thenReturn(List.of("a","b"));
        doThrow(new IllegalStateException()).when(service).evaluate("a");
        var scheduler=new ProductAlertScheduler(jdbc,service);
        scheduler.tick();scheduler.tick();
        verify(service,times(2)).evaluate("b");
        verify(jdbc,times(2)).queryForList(anyString(),eq(String.class),eq(""));
    }
    @Test void fullBatchContinuesFromLastIdBeforeWrapping() {
        var jdbc=mock(JdbcTemplate.class);var service=mock(ProductAlertService.class);
        var ids=IntStream.range(0,100).mapToObj(i->String.format("%03d",i)).toList();
        when(jdbc.queryForList(anyString(),eq(String.class),eq(""))).thenReturn(ids);
        when(jdbc.queryForList(anyString(),eq(String.class),eq("099"))).thenReturn(List.of());
        var scheduler=new ProductAlertScheduler(jdbc,service);
        scheduler.tick();scheduler.tick();scheduler.tick();
        verify(service,times(2)).evaluate("099");
        verify(jdbc).queryForList(anyString(),eq(String.class),eq("099"));
    }
}
