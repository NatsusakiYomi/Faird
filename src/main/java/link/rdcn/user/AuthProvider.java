package link.rdcn.user;

import link.rdcn.server.exception.AuthException;

/**
 * @Author renhao
 * @Description:
 * @Data 2025/6/24 10:59
 * @Modified By:
 */
public interface AuthProvider {

    /**
     * 用户认证，成功返回认证后的保持用户登录状态的凭证
     */
    AuthenticatedUser authenticate(Credentials credentials) throws AuthException;

    /**
     * 判断用户是否具有某项权限
     */
    boolean authorize(AuthenticatedUser user, String dataFrameName);
}
