 package dev.lukka.oculus.managers;

import org.bukkit.plugin.Plugin;
import org.flywaydb.core.Flyway;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseManager {
    private static DatabaseManager instance;
    private final Plugin plugin;
    private final String url;

    private DatabaseManager(Plugin plugin) {
        this.plugin = plugin;
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        File dbFile = new File(dataFolder, "oculus.db");
        this.url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        init();
    }

    public static void initialize(Plugin plugin) {
        if (instance == null) {
            instance = new DatabaseManager(plugin);
        }
    }

    public static DatabaseManager getInstance() {
        return instance;
    }

    private void init() {
        Flyway flyway = Flyway.configure(plugin.getClass().getClassLoader())
                .dataSource(url, "", "")
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
    }

    public Connection getConnection() throws SQLException {
        org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
        config.setBusyTimeout(5000);
        config.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);
        return config.createConnection(url);
    }
}
