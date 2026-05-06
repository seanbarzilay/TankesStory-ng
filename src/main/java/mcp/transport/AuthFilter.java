package mcp.transport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class AuthFilter {

    public enum Result { OK, UNAUTHORIZED }

    private final byte[] expectedToken;

    public AuthFilter(String expectedToken) {
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    public Result check(String authorizationHeader) {
        if (authorizationHeader == null) return Result.UNAUTHORIZED;
        String trimmed = authorizationHeader.trim();
        if (!trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) return Result.UNAUTHORIZED;
        String token = trimmed.substring(7).trim();
        byte[] got = token.getBytes(StandardCharsets.UTF_8);
        if (got.length != expectedToken.length) {
            // run isEqual anyway against a same-length buffer to keep timing flat
            MessageDigest.isEqual(expectedToken, expectedToken);
            return Result.UNAUTHORIZED;
        }
        return MessageDigest.isEqual(got, expectedToken) ? Result.OK : Result.UNAUTHORIZED;
    }
}
