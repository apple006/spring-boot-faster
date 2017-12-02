package ewing.user;

import ewing.config.BaseDao;
import ewing.application.paging.Page;
import ewing.application.paging.Pager;
import ewing.entity.User;
import ewing.security.RoleAsAuthority;
import ewing.security.SecurityUser;

import java.util.List;

/**
 * 用户数据访问接口。
 */
public interface UserDao extends BaseDao {

    Page<User> findUsers(Pager pager, String username, String roleName);

    SecurityUser getByUsername(String username);

    List<RoleAsAuthority> getUserRoles(Long userId);

    List<PermissionTree> getUserPermissions(Long userId);

}
