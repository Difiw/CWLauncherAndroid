package ru.cw.netguard;

import java.lang.instrument.Instrumentation;
import java.security.Permission;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Java agent for developer builds: refuses every outgoing connection to the listed hosts and
 * addresses (the Common World server). Everything else is allowed. The list comes from the
 * system property cw.blockhosts, comma separated.
 */
public final class Guard extends SecurityManager {
    private final Set<String> blocked = new HashSet<>();

    private Guard(String list) {
        for (String item : list.split(",")) {
            String s = item.trim().toLowerCase(Locale.ROOT);
            if (!s.isEmpty()) blocked.add(s);
        }
    }

    public static void premain(String arg, Instrumentation inst) {
        String list = System.getProperty("cw.blockhosts", "");
        if (list.isBlank()) return;
        try {
            System.setSecurityManager(new Guard(list));
        } catch (Throwable t) {
            System.err.println("CW netguard: " + t);
        }
    }

    @Override
    public void checkPermission(Permission p) {
    }

    @Override
    public void checkPermission(Permission p, Object context) {
    }

    @Override
    public void checkConnect(String host, int port) {
        check(host);
    }

    @Override
    public void checkConnect(String host, int port, Object context) {
        check(host);
    }

    private void check(String host) {
        if (host == null) return;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (blocked.contains(h)) throw new SecurityException("CW: сервер недоступен в этой сборке");
    }
}
