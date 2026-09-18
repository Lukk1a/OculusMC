package dev.lukka.oculus.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.websocket.WsContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArraySet;

public final class ConsoleService {
    private final int maxHistory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentLinkedDeque<ConsoleEvent> history = new ConcurrentLinkedDeque<>();
    private final CopyOnWriteArraySet<WsContext> clients = new CopyOnWriteArraySet<>();
    private final Plugin plugin;
    private ConsoleAppender appender;

    public ConsoleService(Plugin plugin) {
        this.plugin = plugin;
        this.maxHistory = plugin.getConfig().getInt("console.history", 300);
    }

    public void start() {
        Logger root = (Logger) LogManager.getRootLogger();
        appender = new ConsoleAppender(this::publish);
        appender.start();
        root.addAppender(appender);
    }

    public void stop() {
        Logger root = (Logger) LogManager.getRootLogger();
        if (appender != null) {
            root.removeAppender(appender);
            appender.stop();
            appender = null;
        }
        clients.clear();
    }

    public void addClient(WsContext context) {
        clients.add(context);
    }

    public void sendHistory(WsContext context) {
        for (ConsoleEvent event : List.copyOf(history)) {
            send(context, event);
        }
    }

    public List<ConsoleEvent> getHistory() {
        return List.copyOf(history);
    }

    public void disconnect(WsContext context) {
        clients.remove(context);
    }

    private void publish(ConsoleEvent event) {
        history.addLast(event);
        while (history.size() > maxHistory) {
            history.pollFirst();
        }
        for (WsContext client : clients) {
            send(client, event);
        }
    }

    private void send(WsContext context, ConsoleEvent event) {
        try {
            context.send(mapper.writeValueAsString(event));
        } catch (Exception error) {
            clients.remove(context);
        }
    }
}
