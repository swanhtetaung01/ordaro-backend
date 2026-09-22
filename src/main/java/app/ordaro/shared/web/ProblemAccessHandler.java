package app.ordaro.shared.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Spring Security's own 401 and 403 answers carry no body, so a client cannot tell them from a
 * crash. This writes the same {@code application/problem+json} shape with a {@code code} that
 * {@link ApiExceptionHandler} produces for every other error.
 */
@Component
public class ProblemAccessHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper json;

    public ProblemAccessHandler(ObjectMapper json) {
        this.json = json;
    }

    /** No token, or one that is expired, forged or names a membership that no longer holds. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authenticationException) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, "unauthenticated",
                "a valid access token is required");
    }

    /** A verified session whose role or kind does not reach this endpoint. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "forbidden",
                "this session is not allowed to do that");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code,
            String detail) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        ProblemDetail problem = ApiExceptionHandler.problem(status, code, detail);
        problem.setInstance(java.net.URI.create(request.getRequestURI()));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(problem));
    }
}
