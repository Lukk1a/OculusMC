package dev.lukka.oculus.console;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class ConsoleAppender extends AbstractAppender {
    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[[;\\d]*m");
    private final Consumer<ConsoleEvent> consumer;

    public ConsoleAppender(Consumer<ConsoleEvent> consumer) {
        super("OculusConsoleAppender", null, null, true, Property.EMPTY_ARRAY);
        this.consumer = consumer;
    }

    @Override
    public void append(LogEvent event) {
        if (event == null) {
            return;
        }
        String message = ANSI_PATTERN.matcher(event.getMessage().getFormattedMessage()).replaceAll("");
        consumer.accept(new ConsoleEvent(
                event.getTimeMillis(),
                toLevel(event.getLevel()),
                event.getLoggerName(),
                message
        ));
    }

    private static String toLevel(Level level) {
        if (level == null || level.isLessSpecificThan(Level.INFO)) {
            return "DEBUG";
        }
        if (level.isMoreSpecificThan(Level.ERROR)) {
            return "ERROR";
        }
        if (level.isMoreSpecificThan(Level.WARN)) {
            return "WARN";
        }
        return "INFO";
    }
}
