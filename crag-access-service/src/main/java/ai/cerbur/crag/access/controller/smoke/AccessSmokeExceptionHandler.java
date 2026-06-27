package ai.cerbur.crag.access.controller.smoke;

import ai.cerbur.crag.access.core.apikey.ApiKeyStateException;
import ai.cerbur.crag.access.core.apikey.ScopeBlockedException;
import ai.cerbur.crag.access.core.identity.InvalidCredentialsException;
import ai.cerbur.crag.access.core.identity.UsernameConflictException;
import ai.cerbur.crag.access.core.membership.LastOwnerException;
import ai.cerbur.crag.access.core.membership.MembershipAuthorizationException;
import ai.cerbur.crag.access.core.membership.MembershipNotFoundException;
import ai.cerbur.crag.access.core.membership.MembershipStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** smoke HTTP 异常映射，仅 {@code smoke} Profile 生效。映射领域异常到稳定 HTTP 状态，不泄漏堆栈、SQL 或凭据。 */
@Profile("smoke")
@RestControllerAdvice
public class AccessSmokeExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(AccessSmokeExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Void> handleValidation(MethodArgumentNotValidException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Void> handleInvalidArgument(IllegalArgumentException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
  }

  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<Void> handleInvalidCredentials(InvalidCredentialsException e) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
  }

  @ExceptionHandler(UsernameConflictException.class)
  public ResponseEntity<Void> handleConflict(UsernameConflictException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).build();
  }

  @ExceptionHandler(MembershipAuthorizationException.class)
  public ResponseEntity<Void> handleForbidden(MembershipAuthorizationException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
  }

  @ExceptionHandler(MembershipNotFoundException.class)
  public ResponseEntity<Void> handleNotFound(MembershipNotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
  }

  @ExceptionHandler({
    LastOwnerException.class,
    ScopeBlockedException.class,
    MembershipStateException.class,
    ApiKeyStateException.class
  })
  public ResponseEntity<Void> handlePrecondition(RuntimeException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).build();
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Void> handleResponseStatus(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode()).build();
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Void> handleInternal(Exception e) {
    log.error("Unhandled access smoke exception", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
  }
}
