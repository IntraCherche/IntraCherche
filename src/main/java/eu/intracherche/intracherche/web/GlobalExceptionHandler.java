package eu.intracherche.intracherche.web;

import eu.intracherche.intracherche.audit.AuditService;
import eu.intracherche.intracherche.common.InvalidSearchQueryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ldap.AuthenticationException;
import org.springframework.ldap.InvalidNameException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.time.LocalDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AuditService auditService;

    public GlobalExceptionHandler(AuditService auditService) {
        this.auditService = auditService;
    }

    // A path that matches no controller and no static file: the caller's mistake, not a server fault.
    // Left to the catch-all below it came back as a 500 and an ERROR line with a stack trace in app.log
    // for every mistyped URL, stale link or scanner probe.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex, WebRequest request) {
        log.debug("No resource for {}", request.getDescription(false));
        ErrorResponse errorResponse = new ErrorResponse(
            LocalDateTime.now(),
            HttpStatus.NOT_FOUND.value(),
            "Not Found",
            "No such resource.",
            request.getDescription(false)
        );
        return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
    }

    // Handle general exceptions
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, WebRequest request) {
        if (isBrokenPipe(ex)) {
            log.debug("Client disconnected before response could be written for {}", request.getDescription(false));
            return null;
        }
        log.error("Unhandled exception at {}", request.getDescription(false), ex);
        ErrorResponse errorResponse = new ErrorResponse(
            LocalDateTime.now(),
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "Internal Server Error",
            "An internal error occurred. Please contact your administrator.",
            request.getDescription(false)
        );
        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static boolean isBrokenPipe(Throwable t) {
        while (t != null) {
            if (t instanceof IOException io) {
                String msg = io.getMessage();
                if (msg != null && (msg.contains("Broken pipe") || msg.contains("Relais brisé")
                        || msg.contains("Connection reset by peer"))) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    // Handle LDAP authentication exceptions
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleLdapAuthenticationException(AuthenticationException ex, WebRequest request) {
        ErrorResponse errorResponse = new ErrorResponse(
            LocalDateTime.now(),
            HttpStatus.UNAUTHORIZED.value(),
            "Authentication Failed",
            "Invalid Domain Credentials",
            request.getDescription(false)
        );
        return new ResponseEntity<>(errorResponse, HttpStatus.UNAUTHORIZED);
    }

    // Handle bad credentials
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentialsException(BadCredentialsException ex, WebRequest request) {
        ErrorResponse errorResponse = new ErrorResponse(
            LocalDateTime.now(),
            HttpStatus.UNAUTHORIZED.value(),
            "Invalid Credentials",
            "Invalid Domain Credentials",
            request.getDescription(false)
        );
        return new ResponseEntity<>(errorResponse, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex, WebRequest request) {
        String message = ex.getMessage();
        // Check if it's the specific PasswordEncoder error
        if (message != null && message.contains("PasswordEncoder")) {
            return new ResponseEntity<>(new ErrorResponse(
                    LocalDateTime.now(),
                    HttpStatus.UNAUTHORIZED.value(),
                    "Configuration Error",
                    "User not found or password encoder mismatch",
                    request.getDescription(false)
            ), HttpStatus.UNAUTHORIZED);
        }

        // Fallback for other IllegalArguments
        return handleGenericException(ex, request);
    }

    // Rejected Solr local-params/magic-field syntax in a search query (SolrQuerySyntaxGuard) —
    // a 400 the user can act on, not the generic 500 IllegalArgumentException falls back to.
    @ExceptionHandler(InvalidSearchQueryException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSearchQuery(InvalidSearchQueryException ex, WebRequest request) {
        // Nobody types Solr local params by accident: this is what probing the access filter looks like.
        String q = request.getParameter("q");
        auditService.logSecurityEvent("QUERY_REJECTED", q != null ? "requête : " + q : ex.getMessage());
        return new ResponseEntity<>(new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Invalid Search Query",
                ex.getMessage(),
                request.getDescription(false)
        ), HttpStatus.BAD_REQUEST);
    }

    // Handle specific exceptions if needed
    public static class ErrorResponse {
        private LocalDateTime timestamp;
        private int status;
        private String error;
        private String message;
        private String path;

        public ErrorResponse(LocalDateTime timestamp, int status, String error, String message, String path) {
            this.timestamp = timestamp;
            this.status = status;
            this.error = error;
            this.message = message;
            this.path = path;
        }

        // Getters and setters
        public LocalDateTime getTimestamp() { return timestamp; }
        public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
        public int getStatus() { return status; }
        public void setStatus(int status) { this.status = status; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }
}
