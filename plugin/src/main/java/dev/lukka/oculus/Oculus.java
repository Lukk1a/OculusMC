package dev.lukka.oculus;

import org.bukkit.plugin.java.JavaPlugin;
import dev.lukka.oculus.api.BukkitThreadExecutor;
import dev.lukka.oculus.api.ThreadExecutor;
import dev.lukka.oculus.bootstrap.JavalinServer;
import dev.lukka.oculus.managers.DatabaseManager;
import dev.lukka.oculus.managers.PluginManager;
import dev.lukka.oculus.listeners.PlayerListener;
import dev.lukka.oculus.console.ConsoleService;
import dev.lukka.oculus.integrations.apollo.ApolloService;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Oculus extends JavaPlugin {

    private JavalinServer server;
    private ConsoleService consoleService;
    private ApolloService apolloService;
    private ThreadExecutor executor;
    private dev.lukka.oculus.backups.BackupService backupService;
    private dev.lukka.oculus.scheduler.SchedulerService schedulerService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        DatabaseManager.initialize(this);
        PluginManager.getInstance().initialize();
        getServer().getPluginManager().registerEvents(new PlayerListener(), this);

        executor = new BukkitThreadExecutor(this);
        consoleService = new ConsoleService(this);
        consoleService.start();
        
        backupService = new dev.lukka.oculus.backups.BackupService(this, executor);
        schedulerService = new dev.lukka.oculus.scheduler.SchedulerService(this, backupService);
        schedulerService.start();

        dev.lukka.oculus.integrations.apollo.ApolloGateway gateway = null;
        if (getServer().getPluginManager().isPluginEnabled("Apollo-Bukkit") || getServer().getPluginManager().isPluginEnabled("Apollo")) {
            gateway = new dev.lukka.oculus.integrations.apollo.ApolloGatewayImpl();
        }
        apolloService = new ApolloService(this, gateway);

        getServer().getScheduler().runTaskTimerAsynchronously(
                this, DashboardController::collectStats, 600L, 600L);

        DashboardController controller = new DashboardController(this, consoleService, executor);
        server = new JavalinServer(this, controller, consoleService, apolloService);
        server.start();

        int port = getConfig().getInt("http.port", 8080);
        getLogger().info(getDescription().getName() + " enabled on port " + port + "!");
    }

    @Override
    public void onDisable() {
        if (schedulerService != null) schedulerService.stop();
        if (consoleService != null) consoleService.stop();
        if (server != null) server.stop();
        getLogger().info(getDescription().getName() + " has been disabled!");
    }

    public dev.lukka.oculus.backups.BackupService getBackupService() {
        return backupService;
    }



    public Javalin getApp() {
        return server != null ? server.getApp() : null;
    }

    public ThreadExecutor getExecutor() {
        return executor;
    }
}
