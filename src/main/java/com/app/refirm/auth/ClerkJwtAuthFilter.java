package com.app.refirm.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Collections;

@Component
@RequiredArgsConstructor
public class ClerkJwtAuthFilter extends OncePerRequestFilter {

    @Value("${clerk.issuer}")
    private String clerkIssuer;

    private final ClerkJwksProvider jwksProvider;

    // ✅ Tell the filter to ignore CORS preflight requests
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        System.out.println("--> [TRIPWIRE] Request reached the filter for URL: " + request.getRequestURI());

        String uri = request.getRequestURI();
// ✅ Added the admin route to the manual bypass
        if (uri.contains("/webhooks") || uri.contains("/public") || uri.contains("/download")) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");

        // If no Authorization header or not Bearer, let request continue (permitAll routes will succeed, secured routes blocked by Spring Security)
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // ✅ 2. Safely extract and trim the token
            String token = authHeader.substring(7).trim();

            String[] chunks = token.split("\\.");
            if (chunks.length < 3) {
                System.err.println("--> [JWT Filter Error]: Malformed token. Chunks found = " + chunks.length);
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid Jwt Token format");
                return;
            }

            String headerJson = new String(Base64.getUrlDecoder().decode(chunks[0]));
            ObjectMapper mapper = new ObjectMapper();
            JsonNode headerNode = mapper.readTree(headerJson);

            if (!headerNode.has("kid")) {
                System.err.println("--> [JWT Filter Error]: Token header is missing 'kid' property");
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Token header is missing");
                return;
            }

            String kid = headerNode.get("kid").asText();
            PublicKey publicKey = jwksProvider.getPublicKey(kid);

            if (publicKey == null) {
                System.err.println("--> [JWT Filter Error]: Could not find or generate PublicKey for kid: " + kid);
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Public key not found for token");
                return;
            }

            // Verify the token
            Claims claims = Jwts.parser()
                    .verifyWith(publicKey)
                    .clockSkewSeconds(60)
                    .requireIssuer(clerkIssuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String clerkId = claims.getSubject();
            System.out.println("--> [JWT Filter SUCCESS]: Authenticated User ID: " + clerkId);

            UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(
                    clerkId, null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));

            SecurityContextHolder.getContext().setAuthentication(authenticationToken);
            filterChain.doFilter(request, response);

        } catch (Exception e) {
            // ✅ 3. Print exact failure to IntelliJ Console
            System.err.println("--> [JWT Filter FAILED]: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid JWT token: " + e.getMessage());
        }
    }
}