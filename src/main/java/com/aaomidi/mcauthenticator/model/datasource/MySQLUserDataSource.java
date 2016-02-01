package com.aaomidi.mcauthenticator.model.datasource;

import com.aaomidi.mcauthenticator.MCAuthenticator;
import com.aaomidi.mcauthenticator.model.UserData;
import com.aaomidi.mcauthenticator.model.UserDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * @author Joseph Hirschfeld <joe@ibj.io>
 * @date 1/30/16
 */

public class MySQLUserDataSource implements UserDataSource {

    public MySQLUserDataSource(String connectionURL, String username, String password, MCAuthenticator mcAuthenticator1) throws SQLException {
        this.mcAuthenticator = mcAuthenticator1;
        this.updateHook = new UpdateHook() {
            @Override
            public void update(UpdatableFlagData me) {
                toUpdate.add(me);
            }
        };
        pool = new HikariDataSource();
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setJdbcUrl(connectionURL);
        pool.setMaximumPoolSize(1); //If someone ever needs more than 1 connection to _update_ DB info for this,
        //I will be genuinely surprised.
        try (Connection c = pool.getConnection()) {
            ResultSet resultSet = c.createStatement().executeQuery("SHOW TABLES;");
            boolean found = false;
            while (resultSet.next()) {
                if (resultSet.getString(1).equalsIgnoreCase("2fa")) {
                    found = true;
                    break;
                }
            }
            resultSet.close();

            if (found) return;

            c.createStatement().execute("CREATE TABLE 2FA(" +
                    "uuid CHAR(32) PRIMARY KEY," +
                    "ip VARCHAR(255)," +
                    "secret CHAR(16)," +
                    "locked BIT(1));");
            c.createStatement().execute("CREATE INDEX uuid_index ON 2FA (uuid);");
        }
    }

    private final MCAuthenticator mcAuthenticator;
    private final HikariDataSource pool;
    private final UpdateHook updateHook;

    private volatile Set<UpdatableFlagData> toUpdate = new HashSet<>();
    private volatile Set<UUID> toDelete = new HashSet<>();

    @Override
    public UserData getUser(UUID id) throws IOException, SQLException {
        if (Bukkit.isPrimaryThread()) throw new RuntimeException("Primary thread I/O");
        try (Connection c = pool.getConnection()) {
            PreparedStatement p = c.prepareStatement("SELECT uuid, ip, secret, locked FROM 2FA WHERE uuid = ?;");
            p.setString(1, id.toString().replaceAll("-", ""));
            ResultSet rs = p.executeQuery();
            if (rs.next()) {
                return new UpdatableFlagData(mcAuthenticator, updateHook, id,
                        InetAddress.getByName(rs.getString("ip")),
                        rs.getString("secret"),
                        rs.getBoolean("locked"));
            } else {
                return null;
            }
        }
    }

    @Override
    public UserData createUser(UUID id) {
        UpdatableFlagData d = new UpdatableFlagData(mcAuthenticator, updateHook, id, null, null, false);
        toUpdate.add(d);
        return d;
    }

    @Override
    public void destroyUser(UUID id) {
        toDelete.add(id);
    }

    @Override
    public void save() throws IOException, SQLException {
        Set<UpdatableFlagData> update = toUpdate;
        toUpdate = new HashSet<>();
        Set<UUID> delete = toDelete;
        toDelete = new HashSet<>();
        try (Connection c = pool.getConnection()) {
            PreparedStatement updateStatement = c.prepareStatement("INSERT INTO 2FA (uuid, ip, secret, locked) VALUES (?,?,?,?) " +
                    "ON DUPLICATE KEY UPDATE ip = ?, secret = ?, locked = ?");
            for (UpdatableFlagData upd : update) {
                if (delete.contains(upd.getId())) continue;
                updateStatement.setString(1, upd.getId().toString().replaceAll("-", ""));
                updateStatement.setString(2, upd.getLastAddress().getHostAddress());
                updateStatement.setString(3, upd.getSecret());
                updateStatement.setBoolean(4, upd.isLocked(null));
                updateStatement.setString(5, upd.getLastAddress().getHostAddress());
                updateStatement.setString(6, upd.getSecret());
                updateStatement.setBoolean(7, upd.isLocked(null));
                updateStatement.execute();
            }
            PreparedStatement deleteStatement = c.prepareStatement("DELETE FROM 2FA WHERE uuid = ?;");
            for (UUID uuid : delete) {
                deleteStatement.setString(1, uuid.toString().replaceAll("-", ""));
                deleteStatement.execute();
            }
        }
    }

    @Override
    public void invalidateCache() throws IOException {
        //No cache!!!
    }
}