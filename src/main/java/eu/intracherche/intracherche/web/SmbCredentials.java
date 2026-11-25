package eu.intracherche.intracherche.web;

public record SmbCredentials(String user, String pass, String domain) {
    @Override
    public String toString() {
        return "SmbCredentials{" +
                "user='" + user + '\'' +
                "pass='" + pass + '\'' +
                ", domain='" + domain + '\'' +
                '}';
    }
}
