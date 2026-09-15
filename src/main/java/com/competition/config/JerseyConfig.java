package com.competition.config;

import com.competition.resource.*;
import jakarta.ws.rs.ApplicationPath;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.jackson.internal.jackson.jaxrs.json.JacksonJsonProvider;

@ApplicationPath("/")
public class JerseyConfig extends ResourceConfig {
    
    public JerseyConfig() {
        // Register REST resources
        register(CompetitorResource.class);
        register(DisciplineResource.class);
        register(StartResource.class);
        register(ResultResource.class);
        register(RankingResource.class);
        register(BuildInfoResource.class);

        // Map stale-save conflicts and missing records to 409 / 404
        register(ConflictExceptionMapper.class);
        register(RecordNotFoundExceptionMapper.class);
        
        // Register Jackson for JSON
        register(JacksonFeature.class);
        register(JacksonJsonProvider.class);
        
        // Register dependency injection binder
        register(new ApplicationBinder());
        
        // Package scanning for components
        packages("com.competition");
    }
}