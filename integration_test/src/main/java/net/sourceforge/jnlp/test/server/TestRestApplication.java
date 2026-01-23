package net.sourceforge.jnlp.test.server;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.HashSet;
import java.util.Set;

/**
 * JAX-RS application for WildFly deployment.
 */
@ApplicationPath("/api")
public class TestRestApplication extends Application {
    
    @Override
    public Set<Class<?>> getClasses() {
        Set<Class<?>> classes = new HashSet<>();
        classes.add(net.sourceforge.jnlp.test.client.TestServiceImpl.class);
        return classes;
    }
}




