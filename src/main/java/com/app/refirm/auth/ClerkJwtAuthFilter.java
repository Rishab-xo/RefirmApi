package com.app.refirm.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Collections;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClerkJwtAuthFilter extends OncePerRequestFilter {

    @Value("${clerk.issuer}")
    private String clerkIssuer;

    private final ClerkJwksProvider jwksProvider;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        String uri = request.getRequestURI();
        if (uri.contains("/webhooks") || uri.contains("/public") || uri.contains("/download")) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String token = authHeader.substring(7).trim();
            String[] chunks = token.split("\\.");

            if (chunks.length < 3) {
                log.warn("Malformed token detected. Chunks found = {}", chunks.length);
                sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid Jwt Token format");
                return;
            }

            // Safely trim and decode the base64 header to prevent Illegal base64 character 20 errors
            String headerJson = new String(Base64.getUrlDecoder().decode(chunks[0].trim()), StandardCharsets.UTF_8);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode headerNode = mapper.readTree(headerJson);

            if (!headerNode.has("kid")) {
                log.warn("Token header is missing 'kid' property");
                sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Token header is missing kid");
                return;
            }

            String kid = headerNode.get("kid").asText();
            PublicKey publicKey = jwksProvider.getPublicKey(kid);

            if (publicKey == null) {
                log.warn("Could not find or generate PublicKey for kid: {}", kid);
                sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Public key not found for token");
                return;
            }

            Claims claims = Jwts.parser()
                    .verifyWith(publicKey)
                    .clockSkewSeconds(60)
                    .requireIssuer(clerkIssuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String clerkId = claims.getSubject();
            log.debug("Authenticated User ID: {}", clerkId);

            UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(
                    clerkId, null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));

            SecurityContextHolder.getContext().setAuthentication(authenticationToken);
            filterChain.doFilter(request, response);

        } catch (ExpiredJwtException e) {
            log.warn("JWT Token has expired: {}", e.getMessage());
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Token expired. Please refresh your session.");
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT Token: {}", e.getMessage());
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid token.");
        } catch (Exception e) {
            log.error("Unexpected error during JWT authentication", e);
            sendErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Authentication processing error");
        }
    }

    private void sendErrorResponse(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(String.format("{\"error\": \"%s\", \"message\": \"%s\"}",
                status == 401 ? "unauthorized" : "forbidden_or_error", message));
    }
}