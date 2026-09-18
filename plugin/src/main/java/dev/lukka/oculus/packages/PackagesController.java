package dev.lukka.oculus.packages;

import io.javalin.http.Context;
import java.util.Map;

/**
 * Controller for package/plugin management.
 * Route /api/packages/install is unregistered in JavalinServer pending
 * repository domain allowlisting (Hangar/Modrinth) per AGENTS.md Invariant 5.
 */
public class PackagesController {

    public void installPackage(Context ctx) {
        // Unregistered pending repository domain allowlisting per AGENTS.md Invariant 5
        ctx.status(404).json(Map.of("error", "not_found", "detail", "Package installation is currently disabled and unregistered pending repository allowlisting."));
    }
}
