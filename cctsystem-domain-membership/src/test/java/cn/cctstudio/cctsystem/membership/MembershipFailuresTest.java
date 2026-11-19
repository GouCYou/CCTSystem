package cn.cctstudio.cctsystem.membership;

import static org.junit.jupiter.api.Assertions.*;
import cn.cctstudio.cctsystem.core.logging.CctLogger;
import java.util.ArrayList;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

final class MembershipFailuresTest {
    @Test
    void temporaryTimeoutIsInfoWhileDataAndProgrammingErrorsRemainWarnings() {
        var info = new ArrayList<String>();
        var warnings = new ArrayList<Throwable>();
        var logger = new CctLogger() {
            public void info(String m) { info.add(m); }
            public void warn(String m) { fail(m); }
            public void warn(String m, Throwable t) { warnings.add(t); }
            public void error(String m, Throwable t) { fail(m); }
        };
        MembershipFailures.log(logger, "join", new CompletionException(new MembershipException("BRIDGE_TIMEOUT", "temporary", true)));
        assertEquals(1, info.size());
        assertTrue(warnings.isEmpty());
        var invalid = new MembershipException("MEMBERSHIP_RESPONSE_INVALID", "bad payload", true);
        MembershipFailures.log(logger, "join", invalid);
        var bug = new IllegalStateException("bug");
        MembershipFailures.log(logger, "join", bug);
        assertEquals(java.util.List.of(invalid, bug), warnings);
        assertFalse(MembershipFailures.temporaryTransport(new MembershipException("BRIDGE_TIMEOUT", "not retryable", false)));
    }
}
