package eu.intracherche.intracherche.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "active-directory")
public class ActiveDirectoryConfig {
    
    private String urls;
    private String base;
    private String username;
    private String password;
    
    // Getters and setters
    public String getUrls() {
        return urls;
    }
    
    public void setUrls(String urls) {
        this.urls = urls;
    }
    
    public String getBase() {
        return base;
    }
    
    public void setBase(String base) {
        this.base = base;
    }
    
    public String getUsername() {
        return username;
    }
    
    public void setUsername(String username) {
        this.username = username;
    }
    
    public String getPassword() {
        return password;
    }
    
    public void setPassword(String password) {
        this.password = password;
    }
}