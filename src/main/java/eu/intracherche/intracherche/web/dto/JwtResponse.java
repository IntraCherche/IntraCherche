package eu.intracherche.intracherche.web.dto;

public record JwtResponse(
        String token,
        String username,
        String type // usually "Bearer"
) {
    // Canonical constructor simplified
    public JwtResponse(String token, String username) {
        this(token, username, "Bearer");
    }
}
