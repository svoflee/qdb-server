package io.qdb.server.security;

import org.simpleframework.http.Request;
import org.simpleframework.http.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.xml.bind.DatatypeConverter;
import java.io.IOException;

/**
 * Authenticates users.
 */
@Singleton
public class AuthService {

    private final UserRepository userRepository;

    @Inject
    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Authenticate a user using information from req. Returns null if authentication failed in which case
     * an appropriate error code and message will have been written to resp.
     */
    public Auth authenticate(Request req, Response resp) throws IOException {
        String s = req.getValue("Authorization");
        if (s == null) return new Auth();

        if (s.startsWith("Basic ")) {
            try {
                s = new String(DatatypeConverter.parseBase64Binary(s.substring(6)), "UTF8");
                int i = s.indexOf(':');
                if (i > 0) {
                    String username = s.substring(0, i);
                    String password = s.substring(i + 1);
                    User user = userRepository.findByEmail(username);
                    if (user != null && user.doesPasswordMatch(password)) {
                        return new Auth(user, "Basic");
                    }
                }
            } catch (IllegalArgumentException ignore) {
            }
        }

        sendChallenge(resp);
        return null;
    }

    public void sendChallenge(Response resp) {
        resp.setCode(401);
        resp.set("WWW-Authenticate", "basic realm=\"qdb\"");
    }

}
