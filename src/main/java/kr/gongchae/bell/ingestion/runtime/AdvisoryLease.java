package kr.gongchae.bell.ingestion.runtime;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** Supplemental session lock; collect_run UNIQUE INSERT still owns each execution slot. */
@Component
public class AdvisoryLease {
    static final long BACKFILL = 47111001L;
    static final long DETAILS = 47111002L;
    private final DataSource dataSource;
    public AdvisoryLease(DataSource dataSource) { this.dataSource=dataSource; }
    public Optional<Lease> acquire(long key) {
        Connection connection=null;
        try {
            connection=dataSource.getConnection();
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
        @Override public void close() {
            try(connection;var statement=connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                statement.setLong(1,key);statement.execute();
            } catch(SQLException e) {throw new CollectionFailure(CollectionFailure.Code.DATABASE_FAILURE);}
        }
    }
}
