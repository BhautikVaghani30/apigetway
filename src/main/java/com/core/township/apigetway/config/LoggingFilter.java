package com.core.township.apigetway.config;

import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global filter for logging request and response details.
 *
 * <p>This filter captures the incoming request method, path, and headers, as well as the outgoing
 * response status code, headers, and execution duration. Sensitive headers like Authorization and
 * Cookies are masked for security.
 */
@Component
@Slf4j
public class LoggingFilter implements GlobalFilter, Ordered {

  private static final Set<String> SENSITIVE_HEADERS =
      Set.of("authorization", "cookie", "set-cookie");

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    long startTime = System.currentTimeMillis();

    // Log Incoming Request
    logRequest(exchange);

    return chain
        .filter(exchange)
        .then(
            Mono.fromRunnable(
                () -> {
                  // Log Outgoing Response
                  logResponse(exchange, startTime);
                }));
  }

  private void logRequest(ServerWebExchange exchange) {
    var request = exchange.getRequest();
    log.info(
        "GATEWAY REQUEST :: ID: {}, Method: {}, Path: {}",
        request.getId(),
        request.getMethod(),
        request.getPath());

    request
        .getHeaders()
        .forEach(
            (key, value) -> {
              if (isSensitive(key)) {
                log.info("GATEWAY REQUEST HEADER :: {}: [*** MASKED ***]", key);
              } else {
                log.info("GATEWAY REQUEST HEADER :: {}: {}", key, value);
              }
            });
  }

  private void logResponse(ServerWebExchange exchange, long startTime) {
    var response = exchange.getResponse();
    long duration = System.currentTimeMillis() - startTime;

    log.info(
        "GATEWAY RESPONSE :: ID: {}, Status: {}, Duration: {}ms",
        exchange.getRequest().getId(),
        response.getStatusCode(),
        duration);

    response
        .getHeaders()
        .forEach(
            (key, value) -> {
              if (isSensitive(key)) {
                log.info("GATEWAY RESPONSE HEADER :: {}: [*** MASKED ***]", key);
              } else {
                log.info("GATEWAY RESPONSE HEADER :: {}: {}", key, value);
              }
            });
  }

  private boolean isSensitive(String headerName) {
    return SENSITIVE_HEADERS.contains(headerName.toLowerCase());
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
