package dev.lukka.oculus.bootstrap;

import io.javalin.http.Context;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ClientIpResolverTest {

    @Test
    public void testUntrustedPeerIgnoresForwardedHeaders() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.1", "127.0.0.1"));

        Context ctx = mock(Context.class);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(ctx.req()).thenReturn(req);
        when(ctx.ip()).thenReturn("198.51.100.42");
        when(req.getRemoteAddr()).thenReturn("198.51.100.42");
        when(ctx.header("X-Forwarded-For")).thenReturn("203.0.113.50, 10.0.0.1");
        when(ctx.header("X-Real-IP")).thenReturn("203.0.113.50");

        String resolved = resolver.resolve(ctx);
        assertEquals("198.51.100.42", resolved);
    }

    @Test
    public void testTrustedPeerParsesXForwardedFor() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8", "127.0.0.1"));

        Context ctx = mock(Context.class);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(ctx.req()).thenReturn(req);
        when(ctx.ip()).thenReturn("10.0.1.50");
        when(req.getRemoteAddr()).thenReturn("10.0.1.50");
        when(ctx.header("X-Forwarded-For")).thenReturn("203.0.113.99, 10.0.1.50");

        String resolved = resolver.resolve(ctx);
        assertEquals("203.0.113.99", resolved);
    }

    @Test
    public void testTrustedPeerParsesSingleXForwardedFor() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("127.0.0.1"));

        Context ctx = mock(Context.class);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(ctx.req()).thenReturn(req);
        when(ctx.ip()).thenReturn("127.0.0.1");
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        when(ctx.header("X-Forwarded-For")).thenReturn("198.51.100.77");

        String resolved = resolver.resolve(ctx);
        assertEquals("198.51.100.77", resolved);
    }

    @Test
    public void testTrustedPeerFallsBackToXRealIp() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("127.0.0.1"));

        Context ctx = mock(Context.class);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(ctx.req()).thenReturn(req);
        when(ctx.ip()).thenReturn("127.0.0.1");
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        when(ctx.header("X-Forwarded-For")).thenReturn(null);
        when(ctx.header("X-Real-IP")).thenReturn("198.51.100.88");

        String resolved = resolver.resolve(ctx);
        assertEquals("198.51.100.88", resolved);
    }

    @Test
    public void testHandleSetsAttribute() throws Exception {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.1"));

        Context ctx = mock(Context.class);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(ctx.req()).thenReturn(req);
        when(ctx.ip()).thenReturn("10.0.0.1");
        when(req.getRemoteAddr()).thenReturn("10.0.0.1");
        when(ctx.header("X-Forwarded-For")).thenReturn("203.0.113.111");

        resolver.handle(ctx);
        verify(ctx).attribute("client-ip", "203.0.113.111");
    }

    @Test
    public void testEmptyTrustedProxiesRejectsAllForwarded() {
        ClientIpResolver resolver = new ClientIpResolver(List.of());

        Context ctx = mock(Context.class);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(ctx.req()).thenReturn(req);
        when(ctx.ip()).thenReturn("127.0.0.1");
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        when(ctx.header("X-Forwarded-For")).thenReturn("1.2.3.4");

        assertEquals("127.0.0.1", resolver.resolve(ctx));
    }
}
