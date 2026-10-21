package com.competition.portal.resource;

import com.competition.portal.service.Countries;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/** The countries a starter can be registered for, [{code, name}] with English names as the Manager stores them. */
@Path("/countries")
@Produces(MediaType.APPLICATION_JSON)
public class CountriesResource {
    @GET
    public List<Map<String, String>> list() {
        return Countries.list();
    }
}
