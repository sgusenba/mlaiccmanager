package com.competition.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

// Older clients may still send the removed disciplines/team_id/relay_number fields
@JsonIgnoreProperties(ignoreUnknown = true)
public class Competitor {
    @JsonProperty("id")
    private int id;
    
    @JsonProperty("name")
    private String name;
    
    @JsonProperty("gender")
    private String gender;
    
    @JsonProperty("club")
    private String club;
    
    @JsonProperty("email")
    private String email;
    
    @JsonProperty("phone")
    private String phone;
    
    @JsonProperty("address")
    private String address;

    @JsonProperty("country")
    private String country;

    @JsonProperty("starts")
    private Map<String, java.util.List<Start>> starts;
    
    @JsonProperty("created_at")
    private String createdAt;
    
    @JsonProperty("year_of_birth")
    private String yearOfBirth;

    // Incremented on every update; clients send it back so stale saves can be rejected
    @JsonProperty("version")
    private Integer version;

    // Constructors
    public Competitor() {}

    public Competitor(String name) {
        this.name = name;
    }

    // Getters and Setters
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public String getClub() { return club; }
    public void setClub(String club) { this.club = club; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public Map<String, java.util.List<Start>> getStarts() { return starts; }
    public void setStarts(Map<String, java.util.List<Start>> starts) { this.starts = starts; }



    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getYearOfBirth() { return yearOfBirth; }
    public void setYearOfBirth(String yearOfBirth) { this.yearOfBirth = yearOfBirth; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}