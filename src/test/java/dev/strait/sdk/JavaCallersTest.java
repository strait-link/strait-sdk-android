package dev.strait.sdk;

import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Capacitor plugin calls the SDK from Java: the pre-0.9 signatures must still compile next to the new ones. */
class JavaCallersTest {
    @Test
    void oldAndNewSignaturesCompile() {
        MemoryStore store = new MemoryStore();
        HttpTransport engine = (url, method, body) -> new HttpResponse(202, "{}");
        StraitConfig old = new StraitConfig("st_pub_test_x", "https://links.test", Collections.emptyList(), store,
            () -> null, "android", () -> new DeviceFields(411, 2.625, "en", "UTC"), engine, Runnable::run,
            System::currentTimeMillis, "phone");
        StraitConfig withInstallTime = new StraitConfig("st_pub_test_x", "https://links.test", Collections.emptyList(), store,
            () -> null, "android", () -> new DeviceFields(411, 2.625, "en", "UTC"), engine, Runnable::run,
            System::currentTimeMillis, "phone", () -> 1_790_000_000_000L);
        StraitClient c = new StraitClient(withInstallTime);
        assertTrue(new StraitClient(old).trackEvent("purchase", 1.0, "USD", null, null));
        assertTrue(c.trackEvent("purchase", 1.0, "USD", null, null, "ord_1"));
        c.setUserId("user_4821");
        c.setUserId(null);
    }
}
