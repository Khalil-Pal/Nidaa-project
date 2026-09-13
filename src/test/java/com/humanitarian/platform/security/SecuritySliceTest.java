package com.humanitarian.platform.security;

import com.humanitarian.platform.config.SecurityConfig;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.service.UserService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.mockito.Mockito.when;

/**
 * Shared wiring for HTTP-layer authorization tests.
 *
 * Each subclass is a {@code @WebMvcTest} slice that imports the real
 * {@link SecurityConfig} (URL rules, method security, JWT filter) and the real
 * service under test, with repositories mocked. The tests therefore exercise
 * the same authentication entry point, role checks and ownership logic the
 * running application uses, without a database.
 */
@Import({SecurityConfig.class, JwtUtils.class})
@TestPropertySource(properties = {
        "jwt.secret=" + SecuritySliceTest.TEST_JWT_SECRET,
        "jwt.expiration=900000",
        "jwt.refresh-expiration=604800000",
        "app.cors.allowed-origins=http://localhost:8081"
})
abstract class SecuritySliceTest {

    static final String TEST_JWT_SECRET =
            "nidaa-security-slice-test-secret-nidaa-security-slice-test-secret";

    @Autowired protected MockMvc mockMvc;

    @MockBean protected UserDetailsServiceImpl userDetailsService;
    @MockBean protected UserService userService;

    protected static User user(long id, UserRole role) {
        return User.builder()
                .id(id)
                .email(role.name().toLowerCase() + id + "@example.com")
                .fullName(role.name() + " " + id)
                .role(role)
                .isActive(true)
                .isVerified(true)
                .isLocked(false)
                .build();
    }

    /** Makes {@code userService.getCurrentUser()} return the given user. */
    protected User actingAs(long id, UserRole role) {
        User me = user(id, role);
        when(userService.getCurrentUser()).thenReturn(me);
        return me;
    }

    /** A structurally valid access token, signed with the test secret, that expired an hour ago. */
    protected static String expiredAccessToken(String email) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(email)
                .claim("type", "access")
                .issuedAt(new Date(now - 2 * 3_600_000L))
                .expiration(new Date(now - 3_600_000L))
                .signWith(Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
