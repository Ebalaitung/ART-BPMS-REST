package com.artezio.bpm.resources;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class AbstractResourceLoader implements ResourceLoader {

    public final static String APP_PROTOCOL = "embedded:app:";
    public final static String DEPLOYMENT_PROTOCOL = "embedded:deployment:";
    private final static Pattern RESOURCE_KEY_PATTERN = Pattern.compile("(:?embedded:\\w*:)?(.+)");
    private final static Pattern PROTOCOL_PATTERN = Pattern.compile("(embedded:\\w*:)?.*");

    @SuppressWarnings("serial")
    public static String getProtocol(String resourceKey) {
        Matcher matcher = PROTOCOL_PATTERN.matcher(resourceKey);
        return matcher.matches() && DEPLOYMENT_PROTOCOL.equals(matcher.group(1))
                ? DEPLOYMENT_PROTOCOL
                : APP_PROTOCOL;
    }

    protected String getResourcePath(String resourceKey) {
        Matcher matcher = RESOURCE_KEY_PATTERN.matcher(resourceKey);
        matcher.matches();
        return matcher.group(2);
    }

}