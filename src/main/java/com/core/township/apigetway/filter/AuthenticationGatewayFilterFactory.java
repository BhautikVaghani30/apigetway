package com.core.township.apigetway.filter;

import com.core.township.apigetway.exception.CoreServiceException;
import com.core.township.apigetway.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Gateway filter that handles JWT authentication for incoming requests.
 *
 * <p>This filter extracts the JWT token from cookies or the Authorization header, validates it, and
 * propagates user identity headers to downstream services. It is implemented in a non-blocking,
 * reactive manner.
 */
@Component
@Slf4j
public class AuthenticationGatewayFilterFactory
    extends AbstractGatewayFilterFactory<AuthenticationGatewayFilterFactory.Config> {

  private final JwtUtil jwtUtil;
  private static final String AUTH_COOKIE_NAME = "TOWN_AT_TOKEN";
  private static final String HEADER_USER_ID = "X-Auth-User-Id";
  private static final String HEADER_SESSION_ID = "X-Auth-Session-Id";
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String UNAUTHORIZED = "E_GETWAY_1002_UNAUTHORIZED_REQUEST";

  /**
   * Constructs the authentication filter factory.
   *
   * @param jwtUtil Utility for JWT operations.
   */
  public AuthenticationGatewayFilterFactory(JwtUtil jwtUtil) {
    super(Config.class);
    this.jwtUtil = jwtUtil;
  }

  /**
   * Applies the authentication filter logic.
   *
   * @param config The configuration properties.
   * @return A {@link GatewayFilter} that processes the request.
   */
  @Override
  public GatewayFilter apply(Config config) {
    return (exchange, chain) ->
        Mono.justOrEmpty(extractToken(exchange))
            .switchIfEmpty(Mono.error(new CoreServiceException(UNAUTHORIZED)))
            .flatMap(
                token ->
                    // 1. Validate Token (Runnable wrapper catches exceptions)
                    Mono.fromRunnable(() -> jwtUtil.validateToken(token))
                        .then(
                            // 2. Extract Details & Mutate Request
                            Mono.fromCallable(
                                () -> {
                                  String userId = jwtUtil.extractUserId(token);
                                  String sessionId = jwtUtil.extractSessionId(token);
                                  if (log.isDebugEnabled()) {
                                    log.debug(
                                        "AuthenticationGatewayFilterFactory : apply :: "
                                            + "cookies -  userId : {}, sessionId : {}",
                                        userId,
                                        sessionId);
                                  }
                                  ServerHttpRequest.Builder requestMutator =
                                      exchange.getRequest().mutate();

                                  Optional.ofNullable(userId)
                                      .ifPresent(id -> requestMutator.header(HEADER_USER_ID, id));
                                  Optional.ofNullable(sessionId)
                                      .ifPresent(
                                          id -> requestMutator.header(HEADER_SESSION_ID, id));

                                  return requestMutator.build();
                                }))
                        .flatMap(
                            mutatedRequest ->
                                chain.filter(exchange.mutate().request(mutatedRequest).build())))
            .onErrorResume(
                e -> {
                  log.error(
                      "AuthenticationGatewayFilterFactory : apply :: Authentication Failed: {}",
                      e.getMessage());
                  return onError(exchange, HttpStatus.UNAUTHORIZED);
                });
  }

  /**
   * Extracts the JWT token from the request.
   *
   * <p>Checks the cookie first, then falls back to the Authorization header.
   *
   * @param exchange The current server web exchange.
   * @return The token string if found, otherwise null.
   */
  private String extractToken(ServerWebExchange exchange) {
    // 1. Try Cookie
    HttpCookie cookie = exchange.getRequest().getCookies().getFirst(AUTH_COOKIE_NAME);
    if (cookie != null) {
      return cookie.getValue();
    }

    // 2. Try Header
    String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
      return authHeader.substring(BEARER_PREFIX.length());
    }
    return null;
  }

  /**
   * Completes the response with an error status code.
   *
   * @param exchange The current server web exchange.
   * @param httpStatus The HTTP status to set.
   * @return A {@link Mono} indicating completion.
   */
  private Mono<Void> onError(ServerWebExchange exchange, HttpStatus httpStatus) {
    ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(httpStatus);
    return response.setComplete();
  }

  /** Configuration class for the filter. */
  public static class Config {}
}
