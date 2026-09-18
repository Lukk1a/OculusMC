package dev.lukka.oculus.console;

public record ConsoleEvent(long timestamp, String level, String logger, String message) {
}
