package app.nubrick.nubrick;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class EventJavaTest {
    @Test public void javaConstructorsAndCopyRemainUsable() {
        NubrickEvent original = new NubrickEvent("old");
        assertTrue(original.getProperties().isEmpty());
        Map<String, Object> properties = new HashMap<>();
        properties.put("count", 3);
        NubrickEvent event = new NubrickEvent("purchase", properties);
        properties.put("count", 9);
        EventPropertyValue.Integer count = (EventPropertyValue.Integer) event.getProperties().get("count");
        assertEquals(3L, count.getValue());
        assertEquals(event.getProperties(), event.copy("copy").getProperties());
    }
}
