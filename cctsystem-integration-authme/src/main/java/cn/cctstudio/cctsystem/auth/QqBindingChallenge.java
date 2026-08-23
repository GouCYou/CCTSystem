package cn.cctstudio.cctsystem.auth;

import java.time.Instant;

public record QqBindingChallenge(String code, Instant expiresAt, String groupNumber) {
}
