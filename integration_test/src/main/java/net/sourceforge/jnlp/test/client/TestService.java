package net.sourceforge.jnlp.test.client;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST service interface for testing.
 */
@Path("/test")
public interface TestService {
    
    @GET
    @Path("/ping")
    @Produces(MediaType.APPLICATION_JSON)
    Response ping();
    
    @GET
    @Path("/data")
    @Produces(MediaType.APPLICATION_JSON)
    Response getData();
}

