package mcp.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthFilterTest {

    private final AuthFilter filter = new AuthFilter("01234567890123456789abcd");

    @Test
    void check_missingHeader_returnsUnauthorized() {
        assertEquals(AuthFilter.Result.UNAUTHORIZED, filter.check(null));
    }

    @Test
    void check_wrongScheme_returnsUnauthorized() {
        assertEquals(AuthFilter.Result.UNAUTHORIZED, filter.check("Basic abcdef"));
    }

    @Test
    void check_wrongToken_returnsUnauthorized() {
        assertEquals(AuthFilter.Result.UNAUTHORIZED, filter.check("Bearer wrong-token-but-long-enough"));
    }

    @Test
    void check_correctToken_returnsOk() {
        assertEquals(AuthFilter.Result.OK, filter.check("Bearer 01234567890123456789abcd"));
    }

    @Test
    void check_correctTokenWithExtraWhitespace_returnsOk() {
        assertEquals(AuthFilter.Result.OK, filter.check("Bearer  01234567890123456789abcd"));
    }
}
