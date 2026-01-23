package net.sourceforge.jnlp.test.client;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * REST service implementation for testing.
 */
public class TestServiceImpl implements TestService {
    
    private static final Logger logger = Logger.getLogger(TestServiceImpl.class.getName());
    
    @Override
    public Response ping() {
        logger.info("REST service ping() called");
        Map<String, String> response = new HashMap<>();
        response.put("status", "ok");
        response.put("message", "Server is responding");
        response.put("timestamp", String.valueOf(System.currentTimeMillis()));
        return Response.ok(response, MediaType.APPLICATION_JSON).build();
    }
    
    @Override
    public Response getData() {
        logger.info("REST service getData() called");
        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("data", "Test data from WildFly server");
        response.put("server", "WildFly 36");
        response.put("timestamp", System.currentTimeMillis());
        return Response.ok(response, MediaType.APPLICATION_JSON).build();
    }
}




