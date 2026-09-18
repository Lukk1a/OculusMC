package dev.lukka.oculus.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class UserRepositoryTest {

    @Test
    public void testHashPasswordAndVerify() {
        UserRepository repo = new UserRepository();
        String password = "MySecurePassword#2026!";
        String hash = repo.hashPassword(password);

        assertNotNull(hash);
        assertTrue(hash.contains(":"));
        assertTrue(repo.verifyPasswordHash(password, hash));
        assertFalse(repo.verifyPasswordHash("WrongPassword", hash));
    }

    @Test
    public void testVerifyPasswordHashEdgeCases() {
        UserRepository repo = new UserRepository();
        String validHash = repo.hashPassword("test1234");

        assertFalse(repo.verifyPasswordHash(null, validHash));
        assertFalse(repo.verifyPasswordHash("test1234", null));
        assertFalse(repo.verifyPasswordHash("test1234", ""));
        assertFalse(repo.verifyPasswordHash("test1234", "malformed"));
        assertFalse(repo.verifyPasswordHash("test1234", "1000:saltOnly"));
        assertFalse(repo.verifyPasswordHash("test1234", "notANumber:salt:hash"));
        assertFalse(repo.verifyPasswordHash("test1234", "1000:notBase64:notBase64"));
    }
}
