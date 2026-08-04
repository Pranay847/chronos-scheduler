package dev.pranay.chronos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Security switches, both defaulting to the safe position.
 *
 * <p>Escape hatches like these are how production incidents happen, so two rules apply: the
 * default is always the secure value, and turning one off logs a warning loud enough to notice in
 * a startup log.
 *
 * @param requireApiKey    when false, every request is treated as the default tenant. Single-tenant
 *                         local development and the tests that predate authentication. Never true
 *                         in a deployment that faces anyone.
 * @param allowPrivateTargets when true, the SSRF denylist is bypassed. Needed for local testing,
 *                         where the fake receiver is on 127.0.0.1 and every legitimate test target
 *                         is an address the guard exists to block. This one is genuinely dangerous:
 *                         it is the difference between a webhook scheduler and an open proxy into
 *                         your own network.
 */
@ConfigurationProperties(prefix = "chronos.security")
public record SecurityProperties(

        @DefaultValue("true") boolean requireApiKey,

        @DefaultValue("false") boolean allowPrivateTargets
) {}
