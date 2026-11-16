package kr.gongchae.bell.ingestion.runtime;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

/**
 * Supplemental session lock; collect_run UNIQUE INSERT still owns each execution slot.
 * A lease holds its own physical connection outside the Hikari pool and always closes it, so a lock never goes back
 * to the pool inside an idle session, even when unlock fails on a live connection.
 */
@Component
public class AdvisoryLease {
    static final long BACKFILL = 47111001L;
    static final long DETAILS = 47111002L;
    private final DataSource unpooled;

    @Autowired
    public AdvisoryLease(JdbcConnectionDetails db) {
        this(new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()));
    }

    /** @param unpooled must open a new physical connection on every call, never lend one from a pool. */
    AdvisoryLease(DataSource unpooled) { this.unpooled=unpooled; }

    public Optional<Lease> acquire(long key) {
        Connection connection=null;
        try {
            connection=unpooled.getConnection();
            try(var statement=connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                statement.setLong(1,key);
                try(var rs=statement.executeQuery()) {
                    rs.next(); if(rs.getBoolean(1)) return Optional.of(new Lease(connection,key));
                }
            }
            connection.close(); return Optional.empty();
        } catch(SQLException e) {
            if(connection!=null) try {connection.close();} catch(SQLException ignored) {}
            throw new CollectionFailure(CollectionFailure.Code.DATABASE_FAILURE);
        }
    }
    public static final class Lease implements AutoCloseable {
        private final Connection connection;
        private final long key;
        private Lease(Connection connection,long key) {this.connection=connection;this.key=key;}
        public void verify() {
            try(var statement=connection.prepareStatement("SELECT 1")) {statement.executeQuery().close();}
            catch(SQLException e) {throw new CollectionFailure(CollectionFailure.Code.DATABASE_FAILURE);}
        }
        /** Unlocks, then closes the physical connection whatever happened; the session's end releases a lock still held. */
        @Override public void close() {
            try(connection;var statement=connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                statement.setLong(1,key);
                try(var rs=statement.executeQuery()) {
                    if(!rs.next() || !rs.getBoolean(1)) throw new CollectionFailure(CollectionFailure.Code.DATABASE_FAILURE);
                }
            } catch(SQLException e) {throw new CollectionFailure(CollectionFailure.Code.DATABASE_FAILURE);}
        }
    }
}
