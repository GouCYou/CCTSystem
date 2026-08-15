package cn.cctstudio.cctsystem.storage.mysql;

import java.sql.Connection;
import java.sql.SQLException;

public interface DatabaseAccess {
    Connection connection() throws SQLException;

    boolean healthy();
}
