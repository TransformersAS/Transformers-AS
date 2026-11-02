package com.transformersas.marketplace.favorites;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
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
}
