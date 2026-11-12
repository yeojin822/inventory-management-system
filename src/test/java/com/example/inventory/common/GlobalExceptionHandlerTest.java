package com.example.inventory.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.TransactionException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.jpa.JpaSystemException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void DB_연결이_끊겨_발생한_예외는_503이다() {
        SQLException connectionClosed = new SQLException("Connection is closed", "08003");
        JpaSystemException exception = new JpaSystemException(new RuntimeException(connectionClosed));

        ResponseEntity<ProblemDetail> response = handler.handleUnexpected(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody().getProperties()).containsEntry("code", "TEMPORARILY_UNAVAILABLE");
    }

    @Test
    void DB_서버가_종료되며_발생한_예외는_503이다() {
        SQLException adminShutdown = new SQLException("terminating connection due to administrator command", "57P01");

        ResponseEntity<ProblemDetail> response = handler.handleUnexpected(new RuntimeException(adminShutdown));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void 끊긴_커넥션에서_롤백이_실패해도_503이다() {
        TransactionException rollbackFailure = new TransactionException(
                "Unable to rollback against JDBC Connection", new SQLException("Connection is closed"));

        ResponseEntity<ProblemDetail> response = handler.handleUnexpected(new JpaSystemException(rollbackFailure));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void 그_밖의_예외는_500이고_내부_메시지를_노출하지_않는다() {
        ResponseEntity<ProblemDetail> response = handler.handleUnexpected(new IllegalStateException("내부 상태 정보"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getDetail()).isEqualTo("서버 내부 오류가 발생했습니다.");
    }
}
