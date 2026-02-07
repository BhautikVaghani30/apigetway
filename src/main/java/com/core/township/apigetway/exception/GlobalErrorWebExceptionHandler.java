package com.core.township.apigetway.exception;

import com.core.township.apigetway.i18n.CoreResourceManager;
import com.core.township.common.enums.CoreApiStatus;
import com.core.township.common.model.response.CoreMessage;
import com.core.township.common.model.response.CoreMeta;
import com.core.township.common.model.response.CoreResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.autoconfigure.web.reactive.error.AbstractErrorWebExceptionHandler;
import org.springframework.boot.web.reactive.error.ErrorAttributes;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.*;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

/**
 * A global error handler for the API Gateway. It intercepts exceptions and provides a structured,
 * user-friendly JSON response using the common CoreResponse format.
 *
 * <p>This handler is responsible for catching exceptions that occur during the routing or filtering
 * phases of the Gateway and translating them into a standardized API response.
 */
@Component
@Order(-1) // Ensures this handler is prioritized over the default one.
@Slf4j
public class GlobalErrorWebExceptionHandler extends AbstractErrorWebExceptionHandler {

  /** Error code used for generic technical failures. */
  private static final String TECHNICAL_ERROR_CODE =
      "E_GETWAY_1000_SOME-THING-WENT-WRONG-TECHNICAL-ISSUE";

  /** Error code used when a downstream service is unreachable. */
  private static final String SERVICE_UNAVAILABLE_CODE = "E_GETWAY_1001_SERVICE-UNAVAILABLE";

  /**
   * Constructs a new {@code GlobalErrorWebExceptionHandler}.
   *
   * @param errorAttributes The error attributes extractor.
   * @param applicationContext The Spring application context.
   * @param serverCodecConfigurer The configurer for server codecs (readers/writers).
   */
  public GlobalErrorWebExceptionHandler(
      ErrorAttributes errorAttributes,
      ApplicationContext applicationContext,
      ServerCodecConfigurer serverCodecConfigurer) {
    super(errorAttributes, new WebProperties.Resources(), applicationContext);
    super.setMessageWriters(serverCodecConfigurer.getWriters());
    super.setMessageReaders(serverCodecConfigurer.getReaders());
  }

  /**
   * Provides the routing function that routes all error requests to the {@code renderErrorResponse}
   * method.
   *
   * @param errorAttributes The error attributes associated with the current request.
   * @return A {@link RouterFunction} that handles error responses.
   */
  @Override
  protected RouterFunction<ServerResponse> getRoutingFunction(
      final ErrorAttributes errorAttributes) {
    return RouterFunctions.route(RequestPredicates.all(), this::renderErrorResponse);
  }

  /**
   * Renders the final error response using the common {@link CoreResponse} structure.
   *
   * <p>This method extracts the exception from the request, logs it, determines the appropriate
   * error code and HTTP status, and builds the JSON response.
   *
   * @param request The incoming server request containing the error.
   * @return A {@link Mono} emitting the {@link ServerResponse}.
   */
  private Mono<ServerResponse> renderErrorResponse(final ServerRequest request) {
    return Mono.fromSupplier(() -> getError(request))
        .doOnNext(
            error ->
                log.error(
                    "GlobalErrorWebExceptionHandler : renderErrorResponse :: Exception occurred: ",
                    error))
        .flatMap(
            error -> {
              boolean isConnectionRefused = isConnectionRefused(error);
              String messageKey =
                  isConnectionRefused ? SERVICE_UNAVAILABLE_CODE : TECHNICAL_ERROR_CODE;

              String resolvedMessage = CoreResourceManager.getMessageKey(messageKey);
              int statusCode = CoreResourceManager.getHttpCode(resolvedMessage);
              String errorCode = CoreResourceManager.getCode(resolvedMessage);

              HttpStatus httpStatus = resolveHttpStatus(statusCode, isConnectionRefused);
              String finalErrorCode = Optional.ofNullable(errorCode).orElse(messageKey);

              return ServerResponse.status(httpStatus)
                  .contentType(MediaType.APPLICATION_JSON)
                  .body(BodyInserters.fromValue(buildCoreResponse(finalErrorCode)));
            });
  }

  /**
   * Helper method to build the {@link CoreResponse} object matching the common library structure.
   *
   * @param errorCode The error code to include in the response message.
   * @return A populated {@link CoreResponse} object.
   */
  private CoreResponse<?> buildCoreResponse(String errorCode) {
    return CoreResponse.create(
        resource ->
            resource
                .withMeta(createErrorMeta())
                .withData(null)
                .withMessages(
                    Collections.singletonList(
                        CoreMessage.builder()
                            .code(errorCode) // Using the code (e.g., TECHNICAL_ERROR)
                            .type(CoreMessage.CoreMessageType.ERROR.toString())
                            .build())));
  }

  /**
   * Creates the standard metadata for the error response.
   *
   * @return A new {@link CoreMeta} instance with the current timestamp and failure status.
   */
  private CoreMeta createErrorMeta() {
    return CoreMeta.builder()
        .timestamp(Instant.now().toString())
        .status(CoreApiStatus.FAIL.getValue())
        .build();
  }

  /**
   * Iteratively checks if the exception or any of its causes is a {@link ConnectException}.
   *
   * <p>This is used to identify if a downstream service is unreachable (e.g., Connection Refused).
   *
   * @param throwable The exception to check.
   * @return {@code true} if a {@link ConnectException} is found in the cause chain, {@code false}
   *     otherwise.
   */
  private boolean isConnectionRefused(Throwable throwable) {
    while (throwable != null) {
      if (throwable instanceof ConnectException) {
        return true;
      }
      throwable = throwable.getCause();
    }
    return false;
  }

  /**
   * Resolves the HTTP status code based on the resolved status from the resource manager or the
   * exception type.
   *
   * @param statusCode The HTTP status code resolved from the message bundle (if any).
   * @param isConnectionRefused Flag indicating if the error was caused by a connection refusal.
   * @return The determined {@link HttpStatus}.
   */
  private HttpStatus resolveHttpStatus(int statusCode, boolean isConnectionRefused) {
    if (statusCode > 0) {
      return HttpStatus.valueOf(statusCode);
    }
    return isConnectionRefused ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR;
  }
}
