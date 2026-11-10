package io.assay.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Opens a JDBC connection for the repositories in this package.
 *
 * <p>References to the result store's public {@code ConnectionFactory} from package-private
 * collaborators would make the repositories depend back on the facade; this top-level contract
 * keeps the dependency direction facade → repositories.
 */
@FunctionalInterface
interface JdbcConnectionFactory {

    Connection open() throws SQLException;
}
