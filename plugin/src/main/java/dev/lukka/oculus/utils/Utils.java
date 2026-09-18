package dev.lukka.oculus.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.md_5.bungee.api.ChatColor;

public class Utils {
    
    private static final Pattern HEX_PATTERN = Pattern.compile("#[a-fA-F0-9]{6}");

    public static String colorize(String msg) {
        if (msg == null) {
            return "";
        }
        Matcher match = HEX_PATTERN.matcher(msg);
        while (match.find()) {
            String color = msg.substring(match.start(), match.end());
            msg = msg.replace(color, String.valueOf(ChatColor.of(color)));
            match = HEX_PATTERN.matcher(msg);
        }
        return ChatColor.translateAlternateColorCodes('&', msg);
    }
}
