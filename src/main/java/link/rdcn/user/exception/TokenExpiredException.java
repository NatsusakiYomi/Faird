package link.rdcn.user.exception;

/**
 * @Author renhao
 * @Description:
 * @Data 2025/6/24 11:06
 * @Modified By:
 */
public class TokenExpiredException extends AuthException {
    private static final io.grpc.Status status = io.grpc.Status.NOT_FOUND
            .withDescription("Token过期!");

    public TokenExpiredException() {
        super(status);
    }
}
